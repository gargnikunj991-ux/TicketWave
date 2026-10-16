package com.ticketwave.seat;

import com.ticketwave.event.Event;
import com.ticketwave.event.EventRepository;
import com.ticketwave.show.Show;
import com.ticketwave.show.ShowRepository;
import com.ticketwave.show.ShowStatus;
import com.ticketwave.venue.Screen;
import com.ticketwave.venue.ScreenRepository;
import com.ticketwave.venue.ScreenType;
import com.ticketwave.venue.Venue;
import com.ticketwave.venue.VenueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ShowSeatRepositoryTest {

    @MockBean
    private RedissonClient redissonClient;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private ScreenRepository screenRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ShowSeatRepository showSeatRepository;

    @Test
    @DisplayName("Should persist entities and query seat matrix with JOIN FETCH")
    @Transactional
    void testPersistAndFetchSeatMatrix() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("O2 Arena")
                .city("London")
                .address("Peninsula Square, London")
                .build());

        Screen screen = screenRepository.save(Screen.builder()
                .venue(venue)
                .name("Screen 1")
                .screenType(ScreenType.DOLBY_ATMOS)
                .totalCapacity(2)
                .build());

        Event event = eventRepository.save(Event.builder()
                .title("Adele Live")
                .description("Concert")
                .durationMinutes(120)
                .genre("Soul / Pop")
                .rating("U")
                .build());

        Show show = showRepository.save(Show.builder()
                .event(event)
                .screen(screen)
                .startTime(Instant.now().plus(2, ChronoUnit.DAYS))
                .endTime(Instant.now().plus(2, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS))
                .status(ShowStatus.ACTIVE)
                .build());

        Seat seatA1 = seatRepository.save(Seat.builder()
                .screen(screen)
                .rowLabel("A")
                .seatNumber(1)
                .tier(SeatTier.VIP)
                .build());

        Seat seatA2 = seatRepository.save(Seat.builder()
                .screen(screen)
                .rowLabel("A")
                .seatNumber(2)
                .tier(SeatTier.VIP)
                .build());

        showSeatRepository.save(ShowSeat.builder()
                .show(show)
                .seat(seatA1)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(150.00))
                .build());

        showSeatRepository.save(ShowSeat.builder()
                .show(show)
                .seat(seatA2)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(150.00))
                .build());

        List<ShowSeat> matrix = showSeatRepository.findByShowIdWithSeatDetails(show.getId());

        assertThat(matrix).hasSize(2);
        assertThat(matrix.get(0).getSeat().getRowLabel()).isEqualTo("A");
        assertThat(matrix.get(0).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Should reject duplicate seats on the same screen (Database Unique Constraint)")
    void testDuplicatePhysicalSeatThrowsConstraintViolation() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Sydney Opera House")
                .city("Sydney")
                .address("Bennelong Point")
                .build());

        Screen screen = screenRepository.save(Screen.builder()
                .venue(venue)
                .name("Concert Hall")
                .screenType(ScreenType.STANDARD)
                .totalCapacity(10)
                .build());

        seatRepository.saveAndFlush(Seat.builder()
                .screen(screen)
                .rowLabel("B")
                .seatNumber(5)
                .tier(SeatTier.PREMIUM)
                .build());

        assertThatThrownBy(() -> {
            seatRepository.saveAndFlush(Seat.builder()
                    .screen(screen)
                    .rowLabel("B")
                    .seatNumber(5)
                    .tier(SeatTier.PREMIUM)
                    .build());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should trigger OptimisticLockException when concurrent update occurs with stale version")
    void testOptimisticLockingOnShowSeat() {
        Venue venue = venueRepository.save(Venue.builder()
                .name("Wembley")
                .city("London")
                .address("Wembley Park")
                .build());

        Screen screen = screenRepository.save(Screen.builder()
                .venue(venue)
                .name("Main Stage")
                .screenType(ScreenType.IMAX)
                .totalCapacity(10)
                .build());

        Event event = eventRepository.save(Event.builder()
                .title("Rock Tour")
                .durationMinutes(120)
                .genre("Rock")
                .rating("U")
                .build());

        Show show = showRepository.save(Show.builder()
                .event(event)
                .screen(screen)
                .startTime(Instant.now().plus(1, ChronoUnit.DAYS))
                .endTime(Instant.now().plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS))
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .screen(screen)
                .rowLabel("C")
                .seatNumber(1)
                .tier(SeatTier.STANDARD)
                .build());

        ShowSeat showSeat = showSeatRepository.saveAndFlush(ShowSeat.builder()
                .show(show)
                .seat(seat)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(80.00))
                .build());

        Long initialVersion = showSeat.getVersion();
        assertThat(initialVersion).isEqualTo(0L);

        ShowSeat thread1Copy = showSeatRepository.findById(showSeat.getId()).orElseThrow();
        ShowSeat thread2Copy = showSeatRepository.findById(showSeat.getId()).orElseThrow();
        thread2Copy.setStatus(SeatStatus.HELD);
        showSeatRepository.saveAndFlush(thread2Copy);

        thread1Copy.setStatus(SeatStatus.BOOKED);
        assertThatThrownBy(() -> {
            showSeatRepository.saveAndFlush(thread1Copy);
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
