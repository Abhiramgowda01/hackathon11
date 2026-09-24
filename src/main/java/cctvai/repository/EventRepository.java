package cctvai.repository;

import cctvai.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, String> {

    List<Event> findByCameraIdOrderByTimestampDesc(String cameraId, org.springframework.data.domain.Pageable pageable);

    List<Event> findByAnomalyTrueOrderByTimestampDesc(org.springframework.data.domain.Pageable pageable);

    List<Event> findByCameraIdAndAnomalyTrueOrderByTimestampDesc(String cameraId, org.springframework.data.domain.Pageable pageable);

    List<Event> findAllByOrderByTimestampDesc(org.springframework.data.domain.Pageable pageable);

    @Query("select distinct e.cameraId from Event e")
    List<String> findDistinctCameraIds();
}
