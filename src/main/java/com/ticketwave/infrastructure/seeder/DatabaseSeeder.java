package com.ticketwave.infrastructure.seeder;

import com.ticketwave.event.Event;
import com.ticketwave.event.EventRepository;
import com.ticketwave.seat.*;
import com.ticketwave.show.Show;
import com.ticketwave.show.ShowRepository;
import com.ticketwave.show.ShowStatus;
import com.ticketwave.venue.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 🌱 Realistic Database Seeder
 *
 * Populates real-world arena, screens, events, shows, and tiered seat matrices
 * on application startup (active on all profiles except 'test').
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    private final VenueRepository venueRepository;
    private final ScreenRepository screenRepository;
    private final EventRepository eventRepository;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ShowSeatRepository showSeatRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (venueRepository.count() > 0) {
            log.info("Database already contains data. Skipping seeding.");
            return;
        }

        log.info("Starting TicketWave realistic database seeding...");

        // 1. Create Venue
        Venue msgVenue = Venue.builder()
                .name("Madison Square Garden")
                .city("New York")
                .address("4 Pennsylvania Plaza, New York, NY 10001")
                .build();
        venueRepository.save(msgVenue);

        // 2. Create Screen
        Screen imaxArena = Screen.builder()
                .venue(msgVenue)
                .name("Main Arena IMAX")
                .screenType(ScreenType.IMAX)
                .totalCapacity(60)
                .build();
        screenRepository.save(imaxArena);

        // 3. Create Event
        Event coldplayConcert = Event.builder()
                .title("Coldplay - Music of the Spheres World Tour")
                .description("Global stadium tour featuring immersive laser visual effects and live performances.")
                .durationMinutes(150)
                .genre("Pop Rock")
                .rating("U")
                .build();
        eventRepository.save(coldplayConcert);

        // 4. Create Show
        Instant showStart = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).plus(19, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES);
        Instant showEnd = showStart.plus(150, ChronoUnit.MINUTES);

        Show msgShow = Show.builder()
                .event(coldplayConcert)
                .screen(imaxArena)
                .startTime(showStart)
                .endTime(showEnd)
                .status(ShowStatus.ACTIVE)
                .build();
        showRepository.save(msgShow);

        // 5. Create 60 Physical Seats (Rows A-F, Seats 1-10)
        // Rows A-B: VIP ($250)
        // Rows C-D: PREMIUM ($150)
        // Rows E-F: STANDARD ($80)
        List<Seat> physicalSeats = new ArrayList<>();
        String[] rows = {"A", "B", "C", "D", "E", "F"};

        for (String row : rows) {
            SeatTier tier = switch (row) {
                case "A", "B" -> SeatTier.VIP;
                case "C", "D" -> SeatTier.PREMIUM;
                default -> SeatTier.STANDARD;
            };

            for (int seatNum = 1; seatNum <= 10; seatNum++) {
                physicalSeats.add(Seat.builder()
                        .screen(imaxArena)
                        .rowLabel(row)
                        .seatNumber(seatNum)
                        .tier(tier)
                        .build());
            }
        }
        seatRepository.saveAll(physicalSeats);

        // 6. Create 60 ShowSeats (State Machine Instances)
        List<ShowSeat> showSeats = new ArrayList<>();
        for (Seat seat : physicalSeats) {
            BigDecimal price = switch (seat.getTier()) {
                case VIP -> BigDecimal.valueOf(250.00);
                case PREMIUM -> BigDecimal.valueOf(150.00);
                case STANDARD -> BigDecimal.valueOf(80.00);
            };

            showSeats.add(ShowSeat.builder()
                    .show(msgShow)
                    .seat(seat)
                    .status(SeatStatus.AVAILABLE)
                    .price(price)
                    .build());
        }
        showSeatRepository.saveAll(showSeats);

        log.info("TicketWave seeding complete! Seeded 1 Venue, 1 Screen, 1 Event, 1 Show, and 60 Tiered ShowSeats.");
    }
}
