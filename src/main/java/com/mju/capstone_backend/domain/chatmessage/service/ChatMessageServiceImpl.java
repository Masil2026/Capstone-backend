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
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
    private final TransactionalOperator transactionalOperator;
    private final ObjectMapper objectMapper;
    private final ItineraryServiceImpl itineraryServiceHelper;

    @Override
    public Mono<GetChatRoomMessagesResponse> getMessages(String clerkId, UUID roomId, OffsetDateTime cursor, int limit) {
        return chatRoomRepository.findById(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found.")))
                .flatMap(chatRoom -> {
                    if (!chatRoom.getClerkId().equals(clerkId)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "You do not have permission to access this chat room."));
                    }
                    if (limit < 1 || limit > 100) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "limit must be between 1 and 100."));
                    }

                    Flux<ChatMessage> fetched = cursor == null
                            ? chatMessageRepository.findByRoomIdOrderByCreatedAtDesc(roomId, limit + 1)
                            : chatMessageRepository.findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(roomId, cursor, limit + 1);

                    return fetched.collectList().map(messages -> {
                        boolean hasMore = messages.size() > limit;
                        List<ChatMessage> page = hasMore ? messages.subList(0, limit) : messages;

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
                                            msg.getId(), msg.getRole(), msg.getContent(), actionResult, msg.getCreatedAt());
                                })
                                .toList();

                        OffsetDateTime nextCursor = hasMore ? page.get(page.size() - 1).getCreatedAt() : null;
                        return new GetChatRoomMessagesResponse(roomId, items, nextCursor, hasMore);
                    });
                })
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch messages.")
                );
    }

    @Override
    public Flux<ServerSentEvent<Object>> sendMessage(String clerkId, UUID roomId, String content) {
        return chatRoomRepository.findById(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found.")))
                .flatMap(chatRoom -> {
                    if (!chatRoom.getClerkId().equals(clerkId)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "You do not have permission to access this chat room."));
                    }
                    return Mono.just(chatRoom);
                })
                .flatMapMany(chatRoom ->
                        fastApiChatClient.stream(roomId, content)
                                .concatMap(event -> switch (event) {
                                    case ChatStreamEvent.Chunk chunk -> Mono.<ServerSentEvent<Object>>just(
                                            ServerSentEvent.<Object>builder()
                                                    .event("chunk")
                                                    .data(new MessageChunkResponse(chunk.content()))
                                                    .build()
                                    );
                                    case ChatStreamEvent.Done done -> processAndSave(chatRoom, content, done.payload())
                                            .map(response -> {
                                                try {
                                                    String json = objectMapper.writerWithDefaultPrettyPrinter()
                                                            .writeValueAsString(response);
                                                    return ServerSentEvent.<Object>builder()
                                                            .event("done")
                                                            .data(json)
                                                            .build();
                                                } catch (Exception e) {
                                                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                            "Failed to serialize done payload.");
                                                }
                                            });
                                })
                )
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

    private Mono<MessageDoneResponse> processAndSave(ChatRoom chatRoom, String userContent, FastApiDonePayload payload) {
        return chatMessageRepository.save(ChatMessage.of(chatRoom.getId(), "user", userContent))
                .flatMap(userMsg -> {
                    Mono<ChatMessage> afterUserEmbed = payload.userMessage().embedding() != null
                            ? chatMessageRepository.updateEmbedding(userMsg.getId(),
                                    payload.userMessage().embedding().toString()).thenReturn(userMsg)
                            : Mono.just(userMsg);

                    return afterUserEmbed.flatMap(u ->
                            chatMessageRepository.save(ChatMessage.of(chatRoom.getId(), "assistant",
                                    payload.assistantMessage().content()))
                                    .flatMap(assistantMsg -> {
                                        Mono<ChatMessage> afterAssistantEmbed = payload.assistantMessage().embedding() != null
                                                ? chatMessageRepository.updateEmbedding(assistantMsg.getId(),
                                                        payload.assistantMessage().embedding().toString()).thenReturn(assistantMsg)
                                                : Mono.just(assistantMsg);

                                        return afterAssistantEmbed.flatMap(a -> {
                                            Mono<Void> updateMemory = Mono.empty();
                                            if (payload.memory() != null) {
                                                try {
                                                    String prefJson = payload.memory().preferences() != null
                                                            ? objectMapper.writeValueAsString(payload.memory().preferences())
                                                            : null;
                                                    chatRoom.updateMemory(payload.memory().aiSummary(), prefJson);
                                                    updateMemory = chatRoomRepository.save(chatRoom)
                                                            .doOnError(e -> log.error("Failed to update chat room memory for roomId={}: {}", chatRoom.getId(), e.getMessage()))
                                                            .onErrorComplete()
                                                            .then();
                                                } catch (Exception e) {
                                                    log.error("Failed to update chat room memory for roomId={}: {}", chatRoom.getId(), e.getMessage());
                                                }
                                            }

                                            MessageDoneResponse.MessageItem userItem = new MessageDoneResponse.MessageItem(
                                                    u.getId(), u.getRole(), u.getContent(), u.getCreatedAt());
                                            MessageDoneResponse.MessageItem assistantItem = new MessageDoneResponse.MessageItem(
                                                    a.getId(), a.getRole(), a.getContent(), a.getCreatedAt());

                                            return updateMemory.then(dispatchByType(chatRoom, a.getId(), userItem, assistantItem, payload));
                                        });
                                    })
                    );
                });
    }

    private Mono<MessageDoneResponse> dispatchByType(ChatRoom chatRoom, UUID assistantMsgId,
                                                      MessageDoneResponse.MessageItem userItem,
                                                      MessageDoneResponse.MessageItem assistantItem,
                                                      FastApiDonePayload payload) {
        return switch (payload) {
            case FastApiDonePayload.Chat ignored ->
                    Mono.just(new MessageDoneResponse(userItem, assistantItem, null, null, null, null));

            case FastApiDonePayload.Itinerary itinerary -> {
                if (itinerary.itinerary() == null) {
                    log.warn("type=itinerary but itinerary data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield Mono.just(new MessageDoneResponse(userItem, assistantItem, null, null, null, null));
                }
                yield processItinerary(chatRoom.getId(), assistantMsgId, userItem, assistantItem, itinerary);
            }

            case FastApiDonePayload.Change change -> {
                if (change.change() == null) {
                    log.warn("type=change but change data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield Mono.just(new MessageDoneResponse(userItem, assistantItem, null, null, null, null));
                }
                yield processChange(chatRoom.getId(), assistantMsgId, userItem, assistantItem, change);
            }

            case FastApiDonePayload.Reservation reservation -> {
                if (reservation.reservation() == null) {
                    log.warn("type=reservation but reservation data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield Mono.just(new MessageDoneResponse(userItem, assistantItem, null, null, null, null));
                }
                yield processReservation(chatRoom.getId(), assistantMsgId, userItem, assistantItem, reservation);
            }

            case FastApiDonePayload.Cancel cancel -> {
                if (cancel.cancel() == null) {
                    log.warn("type=cancel but cancel data is null — treating as chat. roomId={}", chatRoom.getId());
                    yield Mono.just(new MessageDoneResponse(userItem, assistantItem, null, null, null, null));
                }
                yield processCancel(chatRoom.getId(), assistantMsgId, userItem, assistantItem, cancel);
            }
        };
    }

    private Mono<MessageDoneResponse> processItinerary(UUID roomId, UUID assistantMsgId,
                                                        MessageDoneResponse.MessageItem userItem,
                                                        MessageDoneResponse.MessageItem assistantItem,
                                                        FastApiDonePayload.Itinerary payload) {
        return itineraryRepository.findByRoomId(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Itinerary not found for this chat room.")))
                .flatMap(itinerary -> {
                    Map<String, List<Map<String, Object>>> existingDayPlans;
                    try {
                        existingDayPlans = objectMapper.readValue(itinerary.getDayPlans(), new TypeReference<>() {});
                    } catch (Exception e) {
                        return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to parse existing day plans."));
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
                        return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to serialize day plans."));
                    }

                    return Mono.from(transactionalOperator.transactional(
                            itineraryLogRepository.save(ItineraryLog.of(itinerary))
                                    .then(Mono.defer(() -> {
                                        itinerary.updateDayPlans(resultJson);
                                        return itineraryRepository.save(itinerary);
                                    }))
                    )).flatMap(savedItinerary -> {
                                Map<String, List<Map<String, Object>>> indexedDayPlans =
                                        itineraryServiceHelper.parseDayPlansWithIndex(resultJson);

                                try {
                                    Map<String, Object> snapshot = new LinkedHashMap<>();
                                    snapshot.put("itineraryId", itinerary.getId());
                                    snapshot.put("destinations", itineraryServiceHelper.parseAsObject(itinerary.getDestinations()));
                                    snapshot.put("startDate", itinerary.getStartDate());
                                    snapshot.put("endDate", itinerary.getEndDate());
                                    snapshot.put("totalDays", itinerary.getTotalDays());
                                    snapshot.put("dayPlans", indexedDayPlans);
                                    return chatMessageRepository.updateActionResult(assistantMsgId,
                                            objectMapper.writeValueAsString(snapshot))
                                            .thenReturn(buildItineraryResponse(userItem, assistantItem, itinerary, indexedDayPlans, savedItinerary.getUpdatedAt()));
                                } catch (Exception e) {
                                    log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
                                    return Mono.just(buildItineraryResponse(userItem, assistantItem, itinerary, indexedDayPlans, savedItinerary.getUpdatedAt()));
                                }
                            });
                });
    }

    private MessageDoneResponse buildItineraryResponse(MessageDoneResponse.MessageItem userItem,
                                                        MessageDoneResponse.MessageItem assistantItem,
                                                        Itinerary itinerary,
                                                        Map<String, List<Map<String, Object>>> indexedDayPlans,
                                                        OffsetDateTime updatedAt) {
        return new MessageDoneResponse(
                userItem, assistantItem,
                new MessageDoneResponse.ItineraryResult(
                        itinerary.getId(),
                        itineraryServiceHelper.parseDestinations(itinerary.getDestinations()),
                        itinerary.getStartDate(),
                        itinerary.getEndDate(),
                        indexedDayPlans,
                        updatedAt),
                null, null, null);
    }

    private Mono<MessageDoneResponse> processChange(UUID roomId, UUID assistantMsgId,
                                                     MessageDoneResponse.MessageItem userItem,
                                                     MessageDoneResponse.MessageItem assistantItem,
                                                     FastApiDonePayload.Change payload) {
        return itineraryRepository.findByRoomId(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Itinerary not found for this chat room.")))
                .flatMap(itinerary -> {
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
                            ? itineraryServiceHelper.adjustDayPlans(itinerary.getDayPlans(), effectiveStart, effectiveEnd)
                            : null;

                    String newDestinationsJson = null;
                    String newChildAgesJson = null;
                    try {
                        if (newDestinations != null) {
                            newDestinationsJson = objectMapper.writeValueAsString(newDestinations);
                        }
                        if (change.childAges() != null) {
                            newChildAgesJson = objectMapper.writeValueAsString(change.childAges());
                        }
                    } catch (Exception e) {
                        return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to process change."));
                    }

                    final String finalDestJson = newDestinationsJson;
                    final String finalChildAgesJson = newChildAgesJson;
                    final LocalDate finalEffectiveStart = effectiveStart;
                    final LocalDate finalEffectiveEnd = effectiveEnd;

                    return Mono.from(transactionalOperator.transactional(
                            itineraryLogRepository.save(ItineraryLog.of(itinerary))
                                    .then(Mono.defer(() -> {
                                        itinerary.updateBasicInfo(
                                                finalDestJson, change.budget(),
                                                change.adultCount(),
                                                change.childCount(), finalChildAgesJson,
                                                updatedDayPlans,
                                                finalEffectiveStart, finalEffectiveEnd);
                                        return itineraryRepository.save(itinerary);
                                    }))
                    )).flatMap(savedItinerary -> {
                                try {
                                    Map<String, Object> snapshot = new LinkedHashMap<>();
                                    snapshot.put("itineraryId", itinerary.getId());
                                    snapshot.put("destinations", itineraryServiceHelper.parseAsObject(itinerary.getDestinations()));
                                    snapshot.put("startDate", itinerary.getStartDate());
                                    snapshot.put("endDate", itinerary.getEndDate());
                                    snapshot.put("totalDays", itinerary.getTotalDays());
                                    snapshot.put("budget", itinerary.getBudget());
                                    snapshot.put("adultCount", itinerary.getAdultCount());
                                    snapshot.put("childCount", itinerary.getChildCount());
                                    snapshot.put("childAges", itineraryServiceHelper.parseAsObject(itinerary.getChildAges()));
                                    return chatMessageRepository.updateActionResult(assistantMsgId,
                                            objectMapper.writeValueAsString(snapshot))
                                            .thenReturn(buildChangeResponse(userItem, assistantItem, itinerary, savedItinerary.getUpdatedAt()));
                                } catch (Exception e) {
                                    log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
                                    return Mono.just(buildChangeResponse(userItem, assistantItem, itinerary, savedItinerary.getUpdatedAt()));
                                }
                            });
                });
    }

    private MessageDoneResponse buildChangeResponse(MessageDoneResponse.MessageItem userItem,
                                                     MessageDoneResponse.MessageItem assistantItem,
                                                     Itinerary itinerary,
                                                     OffsetDateTime updatedAt) {
        return new MessageDoneResponse(
                userItem, assistantItem, null,
                new MessageDoneResponse.ChangeResult(
                        itinerary.getId(),
                        itineraryServiceHelper.parseDestinations(itinerary.getDestinations()),
                        itinerary.getStartDate(),
                        itinerary.getEndDate(),
                        itinerary.getTotalDays(),
                        itinerary.getBudget(),
                        itinerary.getAdultCount(),
                        itinerary.getChildCount(),
                        itineraryServiceHelper.parseChildAges(itinerary.getChildAges()),
                        updatedAt),
                null, null);
    }

    private Mono<MessageDoneResponse> processReservation(UUID roomId, UUID assistantMsgId,
                                                          MessageDoneResponse.MessageItem userItem,
                                                          MessageDoneResponse.MessageItem assistantItem,
                                                          FastApiDonePayload.Reservation payload) {
        return itineraryRepository.findByRoomId(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Itinerary not found for this chat room.")))
                .flatMap(itinerary -> {
                    FastApiDonePayload.ReservationData r = payload.reservation();
                    String detailJson;
                    try {
                        detailJson = objectMapper.writeValueAsString(r.detail());
                    } catch (Exception e) {
                        return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to serialize reservation detail."));
                    }

                    return reservationRepository.save(Reservation.of(
                            itinerary.getId(), r.type(), "confirmed", "ai",
                            r.bookingUrl(), r.externalRefId(), detailJson,
                            r.totalPrice(), r.currency(), r.reservedAt()))
                            .flatMap(saved -> {
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
                                    return chatMessageRepository.updateActionResult(assistantMsgId,
                                            objectMapper.writeValueAsString(snapshot))
                                            .thenReturn(buildReservationResponse(userItem, assistantItem, saved, r.detail()));
                                } catch (Exception e) {
                                    log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
                                    return Mono.just(buildReservationResponse(userItem, assistantItem, saved, r.detail()));
                                }
                            });
                });
    }

    private MessageDoneResponse buildReservationResponse(MessageDoneResponse.MessageItem userItem,
                                                          MessageDoneResponse.MessageItem assistantItem,
                                                          Reservation saved,
                                                          Map<String, Object> detail) {
        return new MessageDoneResponse(
                userItem, assistantItem, null, null,
                new MessageDoneResponse.ReservationResult(
                        saved.getId(), saved.getType(), saved.getStatus(),
                        saved.getBookingUrl(), saved.getExternalRefId(), detail,
                        saved.getTotalPrice(), saved.getCurrency(), saved.getReservedAt()),
                null);
    }

    private Mono<MessageDoneResponse> processCancel(UUID roomId, UUID assistantMsgId,
                                                     MessageDoneResponse.MessageItem userItem,
                                                     MessageDoneResponse.MessageItem assistantItem,
                                                     FastApiDonePayload.Cancel payload) {
        FastApiDonePayload.CancelData c = payload.cancel();

        return reservationRepository.findById(c.reservationId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found.")))
                .flatMap(reservation -> {
                    reservation.update("cancelled", null, null, null, null, c.cancelledAt());
                    return reservationRepository.save(reservation)
                            .flatMap(saved -> {
                                try {
                                    Map<String, Object> snapshot = new LinkedHashMap<>();
                                    snapshot.put("reservationId", saved.getId());
                                    snapshot.put("status", saved.getStatus());
                                    snapshot.put("cancelledAt", saved.getCancelledAt());
                                    return chatMessageRepository.updateActionResult(assistantMsgId,
                                            objectMapper.writeValueAsString(snapshot))
                                            .thenReturn(buildCancelResponse(userItem, assistantItem, saved));
                                } catch (Exception e) {
                                    log.error("Failed to save action_result for assistantMsgId={}: {}", assistantMsgId, e.getMessage());
                                    return Mono.just(buildCancelResponse(userItem, assistantItem, saved));
                                }
                            });
                });
    }

    private MessageDoneResponse buildCancelResponse(MessageDoneResponse.MessageItem userItem,
                                                     MessageDoneResponse.MessageItem assistantItem,
                                                     Reservation saved) {
        return new MessageDoneResponse(
                userItem, assistantItem, null, null, null,
                new MessageDoneResponse.CancelResult(saved.getId(), saved.getStatus(), saved.getCancelledAt()));
    }

    private Map<String, List<Map<String, Object>>> parseDayPlansWithIndex(String dayPlansJson) {
        return itineraryServiceHelper.parseDayPlansWithIndex(dayPlansJson);
    }

    private String adjustDayPlans(String currentJson, LocalDate newStart, LocalDate newEnd) {
        return itineraryServiceHelper.adjustDayPlans(currentJson, newStart, newEnd);
    }
}
