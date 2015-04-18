package course;

import java.io.Serializable;
import java.util.Date;


@NoRepositoryBean
public interface HistoricalRepository<T extends AnchoredInfo, ID extends Serializable> extends JpaRepository<T, ID> {

    <S extends T> T saveAndFlush(S entity, Date effectiveFromDate);

    <S extends T> Page<S> findByLastTouchedAfter(Date updatedSince, Pageable pageable);

}
