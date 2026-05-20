package com.mju.capstone_backend.domain.chatmessage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatmessage.dto.ChatStreamEvent;
import com.mju.capstone_backend.domain.chatmessage.dto.FastApiDonePayload;
import com.mju.capstone_backend.domain.chatmessage.dto.GetChatRoomMessagesResponse;
import com.mju.capstone_backend.domain.chatmessage.dto.MessageChunkResponse;
import com.mju.capstone_backend.domain.chatmessage.dto.MessageDoneResponse;
import com.mju.capstone_backend.domain.chatmessage.entity.ChatMessage;
import com.mju.capstone_backend.domain.chatmessage.repository.ChatMessageRepository;
import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import com.mju.capstone_backend.domain.itinerary.entity.ItineraryLog;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryLogRepository;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.itinerary.service.ItineraryServiceImpl;
import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ItineraryRepository itineraryRepository;
    private final ItineraryLogRepository itineraryLogRepository;
    private final ReservationRepository reservationRepository;
    private final FastApiChatClient fastApiChatClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Scheduler dbScheduler;

    @Override
    public Mono<GetChatRoomMessagesResponse> getMessages(String clerkId, UUID roomId, OffsetDateTime cursor, int limit) {
        return Mono.fromCallable(() -> {
            ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found."));

            if (!chatRoom.getClerkId().equals(clerkId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You do not have permission to access this chat room.");
            }

            if (limit < 1 || limit > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "limit must be between 1 and 100.");
            }

            PageRequest pageRequest = PageRequest.of(0, limit + 1);
            List<ChatMessage> fetched = cursor == null
                    ? chatMessageRepository.findByRoomIdOrderByCreatedAtDesc(roomId, pageRequest)
                    : chatMessageRepository.findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(roomId, cursor, pageRequest);

            boolean hasMore = fetched.size() > limit;
            List<ChatMessage> page = hasMore ? fetched.subList(0, limit) : fetched;

            List<GetChatRoomMessagesResponse.MessageItem> items = page.stream()
                    .map(msg -> {
                        Object actionResult = null;
                        if (msg.getActionResult() != null) {
                            try {
                                actionResult = objectMapper.readValue(msg.getActionResult(), Object.class);
                            } catch (Exception e) {
                                log.warn("Failed to parse action_result for messageId={}: {}", msg.getId(), e.getMessage());
                            }
                        }
                        return new GetChatRoomMessagesResponse.MessageItem(
                                msg.getId(),
                                msg.getRole(),
                                msg.getContent(),
                                actionResult,
                                msg.getCreatedAt()
                        );
                    })
                    .toList();

            OffsetDateTime nextCursor = hasMore ? page.get(page.size() - 1).getCreatedAt() : null;

            return new GetChatRoomMessagesResponse(roomId, items, nextCursor, hasMore);
        }).subscribeOn(dbScheduler)
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch messages.")
                );
    }

    @Override
    public Flux<ServerSentEvent<Object>> sendMessage(String clerkId, UUID roomId, String content) {
        return Mono.fromCallable(() -> {
            ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found."));
            if (!chatRoom.getClerkId().equals(clerkId))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You do not have permission to access this chat room.");
            return chatRoom;
        }).subscribeOn(dbScheduler)
        .flatMapMany(chatRoom -> {
            return fastApiChatClient.stream(roomId, content)
                    .concatMap(event -> switch (event) {
                        case ChatStreamEvent.Chunk chunk -> Mono.<ServerSentEvent<Object>>just(
                                ServerSentEvent.<Object>builder()
                                        .event("chunk")
                                        .data(new MessageChunkResponse(chunk.content()))
                                        .build()
                        );
                        case ChatStreamEvent.Done done -> Mono.fromCallable(
                                () -> processAndSave(chatRoom, content, done.payload())
                        ).subscribeOn(dbScheduler)
                        .map(response -> {
                            try {
                                String json = objectMapper.writerWithDefaultPrettyPrinter()
                                        .writeValueAsString(response);
                                return ServerSentEvent.<Object>builder()
                                        .event("done")
                                        .data(json)
                                        .build();
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to serialize done payload.");
                            }
                        });
                    });
        })
        .onErrorResume(e -> {
            log.error("SSE stream error for roomId={}: {}", roomId, e.getMessage(), e);
            String message;
            int status;
            if (e instanceof ResponseStatusException rse) {
                message = rse.getReason() != null ? rse.getReason() : "An error occurred.";
                status = rse.getStatusCode().value();
            } else {
                message = "Failed to process AI response.";
                status = 500;
            }
            return Flux.just(ServerSentEvent.<Object>builder()
                    .event("error")
                    .data(Map.of("status", status, "message", message))
                    .build());
        });
    }

    private MessageDoneResponse processAndSave(ChatRoom chatRoom, String userContent,
                                               FastApiDonePayload payload) {
        ChatMessage userMsg = chatMessageRepository.save(
                ChatMessage.of(chatRoom.getId(), "user", userContent));
        if (payload.userMessage().embedding() != null) {
            chatMessageRepository.updateEmbedding(userMsg.getId(),
                    payload.userMessage().embedding().toString());
        }

        ChatMessage assistantMsg = chatMessageRepository.save(
                ChatMessage.of(chatRoom.getId(), "assistant", payload.assistantMessage().content()));
        if (payload.assistantMessage().embedding() != null) {
            chatMessageRepository.updateEmbedding(assistantMsg.getId(),
                    payload.assistantMessage().embedding().toString());
        }

        if (payload.memory() != null) {
            try {
                String preferencesJson = payload.memory().preferences() != null
                        ? objectMapper.writeValueAsString(payload.memory().preferences())
                        : null;
                chatRoom.updateMemory(payload.memory().aiSummary(), preferencesJson);
                chatRoomRepository.save(chatRoom);
            } catch (Exception e) {
                log.error("Failed to update chat room memory for roomId={}: {}", chatRoom.getId(), e.getMessage());
            }
        }

        MessageDoneResponse.MessageItem userItem = new MessageDoneResponse.MessageItem(
                userMsg.getId(), userMsg.getRole(), userMsg.getContent(), userMsg.getCreatedAt());
        MessageDoneResponse.MessageItem assistantItem = new MessageDoneResponse.MessageItem(
                assistantMsg.getId(), assistantMsg.getRole(), assistantMsg.getContent(), assistantMsg.getCreatedAt());

        return switch (payload) {
            case FastApiDonePayload.Chat ignored ->
                    new MessageDoneResponse(userItem, assistantItem, null, null, null, null);

            case FastApiDonePayload.Itinerary itinerary -> {
                if (itinerary.itinerary() == null) {
                    log.warn("type=itinerary but itinerary data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield new MessageDoneResponse(userItem, assistantItem, null, null, null, null);
                }
                yield processItinerary(chatRoom.getId(), assistantMsg.getId(), userItem, assistantItem, itinerary);
            }

            case FastApiDonePayload.Change change -> {
                if (change.change() == null) {
                    log.warn("type=change but change data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield new MessageDoneResponse(userItem, assistantItem, null, null, null, null);
                }
                yield processChange(chatRoom.getId(), assistantMsg.getId(), userItem, assistantItem, change);
            }

            case FastApiDonePayload.Reservation reservation -> {
                if (reservation.reservation() == null) {
                    log.warn("type=reservation but reservation data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield new MessageDoneResponse(userItem, assistantItem, null, null, null, null);
                }
                yield processReservation(chatRoom.getId(), assistantMsg.getId(), userItem, assistantItem, reservation);
            }

            case FastApiDonePayload.Cancel cancel -> {
                if (cancel.cancel() == null) {
                    log.warn("type=cancel but cancel data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield new MessageDoneResponse(userItem, assistantItem, null, null, null, null);
                }
                yield processCancel(chatRoom.getId(), assistantMsg.getId(), userItem, assistantItem, cancel);
            }
        };
    }

    private MessageDoneResponse processItinerary(UUID roomId,
                                                 UUID assistantMsgId,
                                                 MessageDoneResponse.MessageItem userItem,
                                                 MessageDoneResponse.MessageItem assistantItem,
                                                 FastApiDonePayload.Itinerary payload) {
        Itinerary itinerary = itineraryRepository.findByRoomId(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Itinerary not found for this chat room."));

        Map<String, List<Map<String, Object>>> existingDayPlans;
        try {
            existingDayPlans = objectMapper.readValue(itinerary.getDayPlans(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse existing day plans.");
        }

        Map<String, List<Map<String, Object>>> merged = new LinkedHashMap<>(existingDayPlans);

        for (Map.Entry<String, List<Map<String, Object>>> entry : payload.itinerary().dayPlans().entrySet()) {
            List<Map<String, Object>> existingItems = existingDayPlans.getOrDefault(entry.getKey(), List.of());
            Map<String, String> existingStatusByTime = new LinkedHashMap<>();
            for (Map<String, Object> item : existingItems) {
                existingStatusByTime.put((String) item.get("time"),
                        (String) item.getOrDefault("status", "todo"));
            }

            List<Map<String, Object>> normalized = entry.getValue().stream()
                    .map(item -> {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("plan_name", item.get("plan_name"));
                        n.put("time", item.get("time"));
                        n.put("place", item.get("place"));
                        n.put("note", item.getOrDefault("note", ""));
                        n.put("cost", item.get("cost"));
                        n.put("status", existingStatusByTime.getOrDefault((String) item.get("time"), "todo"));
                        return n;
                    })
                    .sorted(Comparator.comparing(item ->
                            LocalTime.parse(((String) item.get("time")).split(" ~ ")[0])))
                    .toList();
            merged.put(entry.getKey(), normalized);
        }

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.put(e.getKey(), e.getValue()));

        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize day plans.");
        }

        Itinerary savedItinerary = transactionTemplate.execute(status -> {
            itineraryLogRepository.save(ItineraryLog.of(itinerary));
            itinerary.updateDayPlans(resultJson);
            return itineraryRepository.save(itinerary);
        });

        Map<String, List<Map<String, Object>>> indexedDayPlans = parseDayPlansWithIndex(resultJson);

        // assistant 메시지에 일정 스냅샷 저장
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("itineraryId", itinerary.getId());
            snapshot.put("destinations", itinerary.getDestinations());
            snapshot.put("startDate", itinerary.getStartDate());
            snapshot.put("endDate", itinerary.getEndDate());
            snapshot.put("totalDays", itinerary.getTotalDays());
            snapshot.put("dayPlans", indexedDayPlans);
            chatMessageRepository.updateActionResult(assistantMsgId, objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
        }

        return new MessageDoneResponse(
                userItem, assistantItem,
                new MessageDoneResponse.ItineraryResult(
                        itinerary.getId(),
                        itinerary.getDestinations(),
                        itinerary.getStartDate(),
                        itinerary.getEndDate(),
                        indexedDayPlans,
                        savedItinerary.getUpdatedAt()),
                null, null, null);
    }

    private MessageDoneResponse processChange(UUID roomId,
                                              UUID assistantMsgId,
                                              MessageDoneResponse.MessageItem userItem,
                                              MessageDoneResponse.MessageItem assistantItem,
                                              FastApiDonePayload.Change payload) {
        Itinerary itinerary = itineraryRepository.findByRoomId(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Itinerary not found for this chat room."));

        FastApiDonePayload.ChangeData change = payload.change();

        List<DestinationItem> newDestinations = change.destinations();
        if (newDestinations != null) {
            ItineraryServiceImpl.validateDestinations(newDestinations);
        }

        LocalDate effectiveStart = newDestinations != null
                ? newDestinations.get(0).startDate()
                : itinerary.getStartDate();
        LocalDate effectiveEnd = newDestinations != null
                ? newDestinations.get(newDestinations.size() - 1).endDate()
                : itinerary.getEndDate();

        boolean dateChanged = !effectiveStart.equals(itinerary.getStartDate())
                || !effectiveEnd.equals(itinerary.getEndDate());
        String updatedDayPlans = dateChanged
                ? adjustDayPlans(itinerary.getDayPlans(), effectiveStart, effectiveEnd)
                : null;

        Itinerary savedItinerary = transactionTemplate.execute(status -> {
            itineraryLogRepository.save(ItineraryLog.of(itinerary));
            itinerary.updateBasicInfo(
                    newDestinations, change.budget(),
                    change.adultCount(),
                    change.childCount(), change.childAges(),
                    updatedDayPlans);
            return itineraryRepository.save(itinerary);
        });

        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("itineraryId", itinerary.getId());
            snapshot.put("destinations", itinerary.getDestinations());
            snapshot.put("startDate", itinerary.getStartDate());
            snapshot.put("endDate", itinerary.getEndDate());
            snapshot.put("totalDays", itinerary.getTotalDays());
            snapshot.put("budget", itinerary.getBudget());
            snapshot.put("adultCount", itinerary.getAdultCount());
            snapshot.put("childCount", itinerary.getChildCount());
            snapshot.put("childAges", itinerary.getChildAges());
            chatMessageRepository.updateActionResult(assistantMsgId, objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
        }

        return new MessageDoneResponse(
                userItem, assistantItem, null,
                new MessageDoneResponse.ChangeResult(
                        itinerary.getId(),
                        itinerary.getDestinations(),
                        itinerary.getStartDate(),
                        itinerary.getEndDate(),
                        itinerary.getTotalDays(),
                        itinerary.getBudget(),
                        itinerary.getAdultCount(),
                        itinerary.getChildCount(),
                        itinerary.getChildAges(),
                        savedItinerary.getUpdatedAt()),
                null, null);
    }

    private MessageDoneResponse processReservation(UUID roomId,
                                                   UUID assistantMsgId,
                                                   MessageDoneResponse.MessageItem userItem,
                                                   MessageDoneResponse.MessageItem assistantItem,
                                                   FastApiDonePayload.Reservation payload) {
        Itinerary itinerary = itineraryRepository.findByRoomId(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Itinerary not found for this chat room."));

        FastApiDonePayload.ReservationData r = payload.reservation();

        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(r.detail());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize reservation detail.");
        }

        Reservation saved = reservationRepository.save(
                Reservation.of(
                        itinerary.getId(),
                        r.type(),
                        "confirmed",
                        "ai",
                        r.bookingUrl(),
                        r.externalRefId(),
                        detailJson,
                        r.totalPrice(),
                        r.currency(),
                        r.reservedAt()
                )
        );

        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservationId", saved.getId());
            snapshot.put("type", saved.getType());
            snapshot.put("status", saved.getStatus());
            snapshot.put("bookingUrl", saved.getBookingUrl());
            snapshot.put("externalRefId", saved.getExternalRefId());
            snapshot.put("detail", r.detail());
            snapshot.put("totalPrice", saved.getTotalPrice());
            snapshot.put("currency", saved.getCurrency());
            snapshot.put("reservedAt", saved.getReservedAt());
            chatMessageRepository.updateActionResult(assistantMsgId, objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
        }

        return new MessageDoneResponse(
                userItem, assistantItem, null, null,
                new MessageDoneResponse.ReservationResult(
                        saved.getId(),
                        saved.getType(),
                        saved.getStatus(),
                        saved.getBookingUrl(),
                        saved.getExternalRefId(),
                        r.detail(),
                        saved.getTotalPrice(),
                        saved.getCurrency(),
                        saved.getReservedAt()
                ),
                null);
    }

    private MessageDoneResponse processCancel(UUID roomId,
                                              UUID assistantMsgId,
                                              MessageDoneResponse.MessageItem userItem,
                                              MessageDoneResponse.MessageItem assistantItem,
                                              FastApiDonePayload.Cancel payload) {
        FastApiDonePayload.CancelData c = payload.cancel();

        Reservation reservation = reservationRepository.findById(c.reservationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Reservation not found."));

        reservation.update("cancelled", null, null, null, null, c.cancelledAt());
        reservationRepository.save(reservation);

        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservationId", reservation.getId());
            snapshot.put("status", reservation.getStatus());
            snapshot.put("cancelledAt", reservation.getCancelledAt());
            chatMessageRepository.updateActionResult(assistantMsgId, objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
        }

        return new MessageDoneResponse(
                userItem, assistantItem, null, null, null,
                new MessageDoneResponse.CancelResult(
                        reservation.getId(),
                        reservation.getStatus(),
                        reservation.getCancelledAt()
                )
        );
    }

    private String adjustDayPlans(String currentJson, LocalDate newStart, LocalDate newEnd) {
        try {
            Map<String, Object> dayPlans = objectMapper.readValue(currentJson, new TypeReference<>() {});
            dayPlans.keySet().removeIf(key -> {
                LocalDate date = LocalDate.parse(key);
                return date.isBefore(newStart) || date.isAfter(newEnd);
            });
            LocalDate cursor = newStart;
            while (!cursor.isAfter(newEnd)) {
                dayPlans.putIfAbsent(cursor.toString(), List.of());
                cursor = cursor.plusDays(1);
            }
            Map<String, Object> sorted = new LinkedHashMap<>();
            dayPlans.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return objectMapper.writeValueAsString(sorted);
        } catch (Exception e) {
            return currentJson;
        }
    }

    private Map<String, List<Map<String, Object>>> parseDayPlansWithIndex(String dayPlansJson) {
        try {
            Map<String, List<Map<String, Object>>> raw =
                    objectMapper.readValue(dayPlansJson, new TypeReference<>() {});

            Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : raw.entrySet()) {
                List<Map<String, Object>> items = new ArrayList<>(entry.getValue());
                items.sort(Comparator.comparing(item -> {
                    String time = (String) item.get("time");
                    return LocalTime.parse(time.split(" ~ ")[0]);
                }));

                List<Map<String, Object>> indexed = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> withIndex = new LinkedHashMap<>();
                    withIndex.put("index", i);
                    withIndex.putAll(items.get(i));
                    indexed.add(withIndex);
                }
                result.put(entry.getKey(), indexed);
            }
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
