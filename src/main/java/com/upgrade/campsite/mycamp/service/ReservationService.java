package com.upgrade.campsite.mycamp.service;

import com.upgrade.campsite.mycamp.exceptions.BusinessException;
import com.upgrade.campsite.mycamp.constants.StatusCodeReservation;
import com.upgrade.campsite.mycamp.jms.ReservationReceiver;
import com.upgrade.campsite.mycamp.model.Reservation;
import com.upgrade.campsite.mycamp.model.dtos.ReservationsStatusDto;
import com.upgrade.campsite.mycamp.repository.ReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private static final String JMSX_GROUP_ID = "JMSXGroupID";
    private static final String MY_CAMP_QUEUE_GROUP_NAME = "MyCampGroupQueue";
    private static final int LIMIT_OF_DATE_FOR_RESERVATIONS = 3;
    private static final int MINIMUM_LIMIT_OF_DAYS_TO_MAKE_RESERVATIONS = 1;
    private static final int MAXIMUM_LIMIT_OF_DAYS_TO_MAKE_RESERVATIONS = 30;
    private static final String THE_RESERVATION_WASNT_FOUND = "The reservation wasn't found: %s";

    @Autowired
    private ReservationRepository reservationsRepository;

    @Autowired
    private JmsTemplate jmsTemplate;

    public Reservation findByNumberOfReservation(String numberOfReservation) {
        return reservationsRepository.findByNumberOfReservation(numberOfReservation);
    }

    private Reservation createReservationPending(Reservation reservation) {
        reservation.setNumberOfReservation(UUID.randomUUID().toString());
        reservation.setStatusReservation(StatusCodeReservation.CODE_STATUS_PENDING_RESERVATION);
        reservationsRepository.save(reservation);
        return reservation;
    }

    @Transactional
    public Reservation sendProcessing(Reservation reservation) throws BusinessException {
        validationsReservations(reservation.getArrivalDate(), reservation.getDepartureDate());
        Reservation reservationSaved = createReservationPending(reservation);
        sendingQueueProcessingMessage(reservationSaved);
        return reservationSaved;
    }

    @Transactional
    public Reservation changeReservation(String numberOfReservation, LocalDate arrivalDate, LocalDate departureDate) throws BusinessException {
        Reservation reservation = reservationsRepository.findByNumberOfReservation(numberOfReservation);
        if(reservation == null) {
            throw new BusinessException(String.format(THE_RESERVATION_WASNT_FOUND, numberOfReservation));
        }
        validationsReservations(arrivalDate, departureDate);
        Reservation newReservation = Reservation.builder()
                .numberOfReservation(UUID.randomUUID().toString())
                .arrivalDate(arrivalDate)
                .departureDate(departureDate)
                .user(reservation.getUser())
                .statusReservation(StatusCodeReservation.CODE_STATUS_PENDING_RESERVATION)
                .build();
        reservationsRepository.save(newReservation);
        newReservation.setNumberOfOldReservation(reservation.getNumberOfReservation());
        sendingQueueProcessingMessage(newReservation);
        return newReservation;
    }

    private void sendingQueueProcessingMessage(Reservation reservation) {
        jmsTemplate.send(ReservationReceiver.RESERVATION_QUEUE_NAME, session -> {
            ObjectMessage om = session.createObjectMessage();
            om.setObject(reservation);
            om.setStringProperty(JMSX_GROUP_ID, MY_CAMP_QUEUE_GROUP_NAME);
            return om;
        });
    }

    public Integer existsReservation(Reservation reservation) {
        return reservationsRepository.existsReservation(reservation.getArrivalDate(), reservation.getDepartureDate());
    }

    @Transactional
    public void changeReservationsStatus(Reservation reservation, String codeStatusOfReservation) {
        if(reservation.getId() != null) {
            reservation.setStatusReservation(codeStatusOfReservation);
            reservationsRepository.save(reservation);
        }
    }

    @Transactional
    public Reservation cancelReservation(String numberOfReservation) throws BusinessException {
        Reservation rsvByNumberOfReservation = reservationsRepository.findByNumberOfReservation(numberOfReservation);
        if(rsvByNumberOfReservation != null) {
            rsvByNumberOfReservation.setStatusReservation(StatusCodeReservation.CODE_STATUS_CANCEL_RESERVATION);
        }else {
            throw new BusinessException(String.format(THE_RESERVATION_WASNT_FOUND, numberOfReservation));
        }
        return rsvByNumberOfReservation;
    }

    public ReservationsStatusDto findStatusReservationAcceptance(String numberOfReservation) throws BusinessException {
        Reservation rscByNumberOfReservation = reservationsRepository.findByNumberOfReservation(numberOfReservation);
        if(rscByNumberOfReservation == null) {
            throw new BusinessException(String.format(THE_RESERVATION_WASNT_FOUND, numberOfReservation));
        }
        return ReservationsStatusDto
                .builder()
                .numberOfReservation(rscByNumberOfReservation.getNumberOfReservation())
                .reservationAcceptance(
                        rscByNumberOfReservation
                                .getStatusReservation().equals(StatusCodeReservation.CODE_STATUS_ACCEPT_RESERVATION) ?
                                Boolean.TRUE : Boolean.FALSE).build();
    }

    private void validationOfValidRageDateOfCamp(LocalDate arrivalDate, LocalDate departureDate) throws BusinessException {
        long days = Math.abs(ChronoUnit.DAYS.between(arrivalDate ,departureDate));
        if(days > LIMIT_OF_DATE_FOR_RESERVATIONS) {
            throw new BusinessException("The reservation exceeded the limit of permanence");
        }
    }

    private void validationOfReservationCanDone(LocalDate arrivalDate) throws BusinessException {
        long between = Math.abs(ChronoUnit.DAYS.between(LocalDate.now(), arrivalDate));
        if(between < MINIMUM_LIMIT_OF_DAYS_TO_MAKE_RESERVATIONS) {
            throw new BusinessException(
                    String.format("The reservation exceeded the minimum limit (minimum limit: %d day ahead of arrival) to make reservations",
                            MINIMUM_LIMIT_OF_DAYS_TO_MAKE_RESERVATIONS));
        }
        if(between > MAXIMUM_LIMIT_OF_DAYS_TO_MAKE_RESERVATIONS) {
            throw new BusinessException(
                    String.format("The reservation exceeded the maximum limit (maximum limit: %d days in advance) to make reservations",
                            MAXIMUM_LIMIT_OF_DAYS_TO_MAKE_RESERVATIONS));
        }
    }

    private void validationsReservations(LocalDate arrivalDate, LocalDate departureDate) throws BusinessException {
        validationOfValidRageDateOfCamp(arrivalDate, departureDate);
        validationOfReservationCanDone(arrivalDate);
    }

    public List<Reservation> findByDateRangeAndStatusReservation(String statusReservaion, LocalDate startDate, LocalDate endDate) {
        return reservationsRepository.findByDateRangeAndStatusReservation(statusReservaion, startDate, endDate);
    }
}