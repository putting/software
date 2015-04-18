package course;

import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Date;
import java.util.logging.Logger;


public class HistoricalRepositoryImpl<T extends AnchoredInfo, ID extends Serializable> extends SimpleJpaRepository<T, ID> {

        private static final Logger LOGGER = LoggerFactory.getLogger(AnchoredInfoRepositoryImpl.class);

        private EntityManager em;

        private List<DataChangeEventListener> dataChangeEventListeners = new ArrayList<>();


        public HistoricalRepositoryImpl(JpaEntityInformation<T, ?> entityInformation,

                                          EntityManager entityManager,

                                          List<DataChangeEventListener> dataChangeEventListeners) {

            super(entityInformation, entityManager);

            this.em = entityManager;

            this.dataChangeEventListeners = dataChangeEventListeners;

        }


        public HistoricalRepositoryImpl(Class<T> domainClass, EntityManager entityManager,

                                          List<DataChangeEventListener> dataChangeEventListeners) {

            super(domainClass, entityManager);
            this.em = entityManager;
            this.dataChangeEventListeners = dataChangeEventListeners;

        }


        public <S extends T> S saveAndFlush(S anchor, Date effectiveFromDate) {

            Assert.notNull(anchor);

            Date now = getCurrentTime();

            Operation operation = null;

            if (anchor.isNew()) {

                LOGGER.debug("The anchor {} is new", anchor);

                anchor.setLastTouched(now);
                persistAndFlush(anchor);
                operation = Operation.CREATED;

            }

            S persistedEntity;

            if (anchor.isDirty()) {

                anchor.setTouched(true);

                LOGGER.debug("The anchor {} is dirty", anchor);

                List<HistorizedInfo<?>> entitiesToSave = anchor.getHistorizedEntities();

                for (HistorizedInfo<?> current : entitiesToSave) {

                    if (current.isNew()) {
                        persistNewCurrent(current, now, effectiveFromDate);
                        current.resetDirtyFlag();

                    } else if (current.isDirty()) {

                        mergeExistingCurrent(current, now, effectiveFromDate);
                        current.resetDirtyFlag();
                    }

                }

                anchor.setHasDetail(BooleanCharacter.Y.booleanValue());
                anchor.setLastTouched(now);


                if (!anchor.isCurrent()) {

                    anchor.setDeletedDateTime(now);
                    operation = Operation.DELETED;

                }

                if (operation == null) {

                    operation = Operation.UPDATED;

                }

                persistedEntity = mergeAndFlush(anchor);

            } else {

                LOGGER.debug("The anchor {} is not dirty", anchor);
                persistedEntity = anchor;

            }

            if (operation != null) {
                notififyInterestedListeners(anchor, operation, dataChangeEventListeners);
            }

            return persistedEntity;

        }



        private void mergeExistingCurrent(HistorizedInfo<?> current, Date now, Date effectiveFromDate) {

            HistorizedInfo<?> previous = current.createCloneOfOriginal();

            if (!current.getEffectiveFrom().equals(previous.getEffectiveFrom())) {

                checkEffectiveFromDateToBeInThePast(current, now);
                checkEffectiveFromDateToBeAfterTheCurrentlyPersisted(current);
            }

            if (current.isDeleted()) {

                markNotCurrent(current, now, effectiveFromDate);
                mergeAndFlush(current);

            } else {

                setEffectiveFrom(current, effectiveFromDate);
                current.setAsAtFrom(now);

                // Current (new data) should have a later created date timestamp than

                // Previous (old data)

                Date originalCreatedDateTime = current.getCreatedDateTime();
                setCreatedAndUpdatedDateTime(current, now, now);
                mergeAndFlush(current);

                // Using the created date timestamp from the original record

                setCreatedAndUpdatedDateTime(previous, originalCreatedDateTime, now);
                markNotCurrent(previous, now, current.getEffectiveFrom());
                persistAndFlush(previous);

            }

        }

        private void markNotCurrent(HistorizedInfo<?> entity, Date now, Date endDate) {

            entity.setEffectiveTo(endDate);
            entity.setAsAtTo(now);
            entity.setCurrent(N);

            // we need to support corrections on intraday basis

            boolean isIntradayChange = DateUtils.isSameDay(entity.getEffectiveTo(),
                    entity.getEffectiveFrom());

            entity.setCorrected(BooleanCharacter.valueOf(isIntradayChange));

        }



        private void persistNewCurrent(HistorizedInfo<?> current, Date now, Date effectiveFromDate) {

            current.setAsAtFrom(now);
            current.setAsAtTo(HistorizedInfo.END_OF_TIME);

            if (current.getEffectiveFrom() == null) {
                setEffectiveFrom(current, effectiveFromDate);
            } else {
                checkEffectiveFromDateToBeInThePast(current, now);
            }

            current.setEffectiveTo(HistorizedInfo.END_OF_TIME);
            current.setCurrent(Y);
            current.setCorrected(N);

            setCreatedAndUpdatedDateTime(current, now, now);
            persistAndFlush(current);

        }


        private void checkEffectiveFromDateToBeInThePast(HistorizedInfo<?> current, Date now) {
            Assert.state(

                    now.after(current.getEffectiveFrom()),

                    format("effectiveFrom date of %s with id %d must not be in the future", current

                            .getClass().getName(), current.getId()));

        }


        private void checkEffectiveFromDateToBeAfterTheCurrentlyPersisted(HistorizedInfo<?> current) {

            HistorizedInfo<?> persisted = current.createCloneOfOriginal();
            boolean sameEffectiveFromDate = current.getEffectiveFrom().equals(
                    persisted.getEffectiveFrom());

            boolean currentAfterPreviousEffectiveFromDate = current.getEffectiveFrom().after(
                    persisted.getEffectiveFrom());

            Assert.state(
                    sameEffectiveFromDate || currentAfterPreviousEffectiveFromDate,
                    format("effectiveFrom date of %s with id %d must not be changed to an earlier date",
                            current.getClass().getName(), current.getId()));

        }

        private <E extends Auditable> void persistAndFlush(E entity) {

            em.persist(entity);
            em.flush();

            LOGGER.info("Saving : {} : into HODS", entity.toIdentityString());
            LOGGER.debug("Saving : {} : into HODS", entity);

        }

        private <E extends Auditable> E mergeAndFlush(E entity) {

            E mergedEntity = em.merge(entity);
            em.flush();

            LOGGER.info("Updating : {} : into HODS", entity.toIdentityString());
            LOGGER.debug("Updating : {} : into HODS", entity);

            return mergedEntity;

        }

        private void setEffectiveFrom(HistorizedInfo<?> current, Date effectiveFromDate) {

            if (effectiveFromDate != null) {

                current.setEffectiveFrom(effectiveFromDate);

            }

        }


        private void setCreatedAndUpdatedDateTime(Historized historised, Date createdDateTime,
                                                  Date updatedDateTime) {

            historised.setCreatedDateTime(createdDateTime);
            historised.setUpdatedDateTime(updatedDateTime);

        }

        Date getCurrentTime() {

            return new Date();

        }

        void setDataChangeEventListeners(List<DataChangeEventListener> dataChangeEventListeners) {

            this.dataChangeEventListeners = dataChangeEventListeners;

        }

}
