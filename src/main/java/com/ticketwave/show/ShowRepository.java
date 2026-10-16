package com.ticketwave.show;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShowRepository extends JpaRepository<Show, Long> {
    List<Show> findByEventIdAndStatus(Long eventId, ShowStatus status);
    List<Show> findByScreenId(Long screenId);
}
