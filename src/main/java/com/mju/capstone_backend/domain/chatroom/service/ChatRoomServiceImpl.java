package com.mju.capstone_backend.domain.chatroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatmessage.entity.ChatMessage;
import com.mju.capstone_backend.domain.chatmessage.repository.ChatMessageRepository;
import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomRequest;
import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.DeleteChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.GetChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.GetChatRoomsResponse;
import com.mju.capstone_backend.domain.chatroom.dto.UpdateChatRoomNameResponse;
import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.itinerary.service.ItineraryServiceImpl;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import com.mju.capstone_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatRoomServiceImpl implements ChatRoomService {

    private static final String INITIAL_AI_MESSAGE =
            "입력하신 정보를 확인했어요. 여행 계획에 더 참고해야 할 만한 사항이 있나요? 없다면 바로 일정을 생성할게요!";

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ItineraryRepository itineraryRepository;
    private final ReservationRepository reservationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public Mono<CreateChatRoomResponse> createChatRoom(String clerkId, CreateChatRoomRequest request) {
        return userRepository.existsById(clerkId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found. Please sign up first.")))
                .flatMap(ignored -> {
                    ItineraryServiceImpl.validateDestinations(request.destinations());

                    if (request.childAges().size() != request.childCount()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "childAges length must match childCount."));
                    }

                    List<DestinationItem> destinations = request.destinations();
                    long totalDays = ChronoUnit.DAYS.between(
                            destinations.get(0).startDate(),
                            destinations.get(destinations.size() - 1).endDate()) + 1;
                    String name = (totalDays - 1) + "박 " + totalDays + "일 " + destinations.get(0).city() + " 여행";

                    String destinationsJson;
                    String childAgesJson;
                    try {
                        destinationsJson = objectMapper.writeValueAsString(destinations);
                        childAgesJson = objectMapper.writeValueAsString(request.childAges());
                    } catch (Exception e) {
                        return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create chat room."));
                    }

                    ChatRoom chatRoom = ChatRoom.of(clerkId, name);

                    return chatRoomRepository.save(chatRoom)
                            .flatMap(savedRoom -> {
                                Itinerary itinerary = Itinerary.of(
                                        savedRoom.getId(),
                                        destinationsJson,
                                        request.budget(),
                                        request.adultCount(),
                                        request.childCount(),
                                        childAgesJson,
                                        destinations.get(0).startDate(),
                                        destinations.get(destinations.size() - 1).endDate()
                                );
                                return itineraryRepository.save(itinerary)
                                        .flatMap(savedItinerary ->
                                                chatMessageRepository.save(ChatMessage.of(savedRoom.getId(), "assistant", INITIAL_AI_MESSAGE))
                                                        .thenReturn(new CreateChatRoomResponse(
                                                                savedRoom.getId(),
                                                                savedRoom.getName(),
                                                                savedItinerary.getId(),
                                                                savedRoom.getClerkId(),
                                                                savedRoom.getCreatedAt(),
                                                                savedRoom.getUpdatedAt()
                                                        ))
                                        );
                            });
                })
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create chat room.")
                );
    }

    @Override
    public Mono<GetChatRoomsResponse> getChatRooms(String clerkId) {
        return userRepository.existsById(clerkId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found. Please sign up first.")))
                .flatMapMany(ignored -> chatRoomRepository.findByClerkIdOrderByUpdatedAtDesc(clerkId))
                .map(room -> new GetChatRoomsResponse.ChatRoomItem(
                        room.getId(),
                        room.getName(),
                        room.getClerkId(),
                        room.getAiSummary(),
                        parsePreferences(room.getPreferences()),
                        room.getCreatedAt(),
                        room.getUpdatedAt()
                ))
                .collectList()
                .map(GetChatRoomsResponse::new)
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch chat rooms.")
                );
    }

    @Override
    public Mono<GetChatRoomResponse> getChatRoom(String clerkId, java.util.UUID roomId) {
        return chatRoomRepository.findById(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found.")))
                .flatMap(chatRoom -> {
                    if (!chatRoom.getClerkId().equals(clerkId)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "You do not have permission to access this chat room."));
                    }
                    return itineraryRepository.findByRoomId(roomId)
                            .map(Itinerary::getId)
                            .switchIfEmpty(Mono.justOrEmpty((java.util.UUID) null))
                            .map(itineraryId -> new GetChatRoomResponse(
                                    chatRoom.getId(),
                                    chatRoom.getName(),
                                    chatRoom.getClerkId(),
                                    chatRoom.getAiSummary(),
                                    parsePreferences(chatRoom.getPreferences()),
                                    itineraryId,
                                    chatRoom.getCreatedAt(),
                                    chatRoom.getUpdatedAt()
                            ));
                })
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch chat room.")
                );
    }

    @Transactional
    @Override
    public Mono<DeleteChatRoomResponse> deleteChatRoom(String clerkId, java.util.UUID roomId) {
        return chatRoomRepository.findById(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found.")))
                .flatMap(chatRoom -> {
                    if (!chatRoom.getClerkId().equals(clerkId)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "You do not have permission to delete this chat room."));
                    }

                    Mono<Void> deleteOp = itineraryRepository.findByRoomId(roomId)
                            .flatMap(itinerary ->
                                    reservationRepository.existsActiveByItineraryId(itinerary.getId())
                                            .flatMap(hasActive -> {
                                                if (hasActive) {
                                                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                                            "Cannot delete chat room with active reservations. Please cancel all reservations first."));
                                                }
                                                return reservationRepository.deleteCancelledByItineraryId(itinerary.getId());
                                            })
                            )
                            .then(Mono.defer(() -> chatRoomRepository.deleteById(roomId)));

                    return deleteOp
                            .thenReturn(new DeleteChatRoomResponse(roomId, true));
                })
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete chat room.")
                );
    }

    @Override
    public Mono<UpdateChatRoomNameResponse> updateChatRoomName(String clerkId, java.util.UUID roomId, String name) {
        return chatRoomRepository.findById(roomId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found.")))
                .flatMap(chatRoom -> {
                    if (!chatRoom.getClerkId().equals(clerkId)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "You do not have permission to update this chat room."));
                    }
                    chatRoom.updateName(name);
                    return chatRoomRepository.save(chatRoom);
                })
                .map(saved -> new UpdateChatRoomNameResponse(saved.getId(), saved.getName(), saved.getUpdatedAt()))
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update chat room name.")
                );
    }

    private Map<String, Object> parsePreferences(String preferences) {
        if (preferences == null) return null;
        try {
            return objectMapper.readValue(preferences, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
