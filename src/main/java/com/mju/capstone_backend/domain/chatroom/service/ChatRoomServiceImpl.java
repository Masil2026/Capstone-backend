package com.mju.capstone_backend.domain.chatroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomRequest;
import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.DeleteChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.GetChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.GetChatRoomsResponse;
import com.mju.capstone_backend.domain.chatroom.dto.UpdateChatRoomNameResponse;
import com.mju.capstone_backend.domain.chatmessage.entity.ChatMessage;
import com.mju.capstone_backend.domain.chatmessage.repository.ChatMessageRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatRoomServiceImpl implements ChatRoomService {

        private static final String INITIAL_AI_MESSAGE = "입력하신 정보를 확인했어요. 여행 계획에 더 참고해야 할 만한 사항이 있나요? 없다면 바로 일정을 생성할게요!";

        private final UserRepository userRepository;
        private final ChatRoomRepository chatRoomRepository;
        private final ChatMessageRepository chatMessageRepository;
        private final ItineraryRepository itineraryRepository;
        private final ReservationRepository reservationRepository;
        private final Scheduler dbScheduler;
        private final ObjectMapper objectMapper;
        private final TransactionTemplate transactionTemplate;

        @Override
        public Mono<CreateChatRoomResponse> createChatRoom(String clerkId, CreateChatRoomRequest request) {
                return Mono.fromCallable(() -> {
                        if (!userRepository.existsById(clerkId)) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found. Please sign up first.");
                        }

                        ItineraryServiceImpl.validateDestinations(request.destinations());

                        if (request.childAges().size() != request.childCount()) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "childAges length must match childCount.");
                        }

                        List<DestinationItem> destinations = request.destinations();
                        long totalDays = ChronoUnit.DAYS.between(
                                destinations.get(0).startDate(),
                                destinations.get(destinations.size() - 1).endDate()) + 1;
                        String name = (totalDays - 1) + "박 " + totalDays + "일 " + destinations.get(0).city() + " 여행";

                        return transactionTemplate.execute(status -> {
                                ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.of(clerkId, name));
                                Itinerary itinerary = itineraryRepository.save(
                                        Itinerary.of(
                                                chatRoom.getId(),
                                                destinations,
                                                request.budget(),
                                                request.adultCount(),
                                                request.childCount(),
                                                request.childAges()
                                        )
                                );
                                chatMessageRepository.save(
                                                ChatMessage.of(chatRoom.getId(), "assistant", INITIAL_AI_MESSAGE));
                                return new CreateChatRoomResponse(
                                                chatRoom.getId(),
                                                chatRoom.getName(),
                                                itinerary.getId(),
                                                chatRoom.getClerkId(),
                                                chatRoom.getCreatedAt(),
                                                chatRoom.getUpdatedAt()
                                );
                        });
                }).subscribeOn(dbScheduler)
                                .onErrorMap(
                                                e -> !(e instanceof ResponseStatusException),
                                                e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create chat room.")
                                );
        }

        @Override
        public Mono<GetChatRoomsResponse> getChatRooms(String clerkId) {
                return Mono.fromCallable(() -> {
                        if (!userRepository.existsById(clerkId)) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found. Please sign up first.");
                        }

                        List<GetChatRoomsResponse.ChatRoomItem> items = chatRoomRepository
                                        .findByClerkIdOrderByUpdatedAtDesc(clerkId)
                                        .stream()
                                        .map(room -> new GetChatRoomsResponse.ChatRoomItem(
                                                        room.getId(),
                                                        room.getName(),
                                                        room.getClerkId(),
                                                        room.getAiSummary(),
                                                        parsePreferences(room.getPreferences()),
                                                        room.getCreatedAt(),
                                                        room.getUpdatedAt()
                                        ))
                                        .toList();

                        return new GetChatRoomsResponse(items);
                }).subscribeOn(dbScheduler)
                                .onErrorMap(
                                                e -> !(e instanceof ResponseStatusException),
                                                e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch chat rooms.")
                                );
        }

        @Override
        public Mono<GetChatRoomResponse> getChatRoom(String clerkId, UUID roomId) {
                return Mono.fromCallable(() -> {
                        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found."));

                        if (!chatRoom.getClerkId().equals(clerkId)) {
                                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                                "You do not have permission to access this chat room.");
                        }

                        UUID itineraryId = itineraryRepository.findByRoomId(roomId)
                                        .map(Itinerary::getId)
                                        .orElse(null);

                        return new GetChatRoomResponse(
                                        chatRoom.getId(),
                                        chatRoom.getName(),
                                        chatRoom.getClerkId(),
                                        chatRoom.getAiSummary(),
                                        parsePreferences(chatRoom.getPreferences()),
                                        itineraryId,
                                        chatRoom.getCreatedAt(),
                                        chatRoom.getUpdatedAt()
                        );
                }).subscribeOn(dbScheduler)
                                .onErrorMap(
                                                e -> !(e instanceof ResponseStatusException),
                                                e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch chat room.")
                                );
        }

        @Override
        public Mono<DeleteChatRoomResponse> deleteChatRoom(String clerkId, UUID roomId) {
                return Mono.fromCallable(() -> {
                        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found."));

                        if (!chatRoom.getClerkId().equals(clerkId)) {
                                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                                "You do not have permission to delete this chat room.");
                        }

                        var itineraryOpt = itineraryRepository.findByRoomId(roomId);

                        itineraryOpt.ifPresent(itinerary -> {
                                if (reservationRepository.existsByItineraryIdAndStatusIn(
                                                itinerary.getId(), List.of("confirmed", "changed"))) {
                                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                        "Cannot delete chat room with active reservations. Please cancel all reservations first.");
                                }
                        });

                        transactionTemplate.execute(status -> {
                                itineraryOpt.ifPresent(itinerary ->
                                                reservationRepository.deleteCancelledByItineraryId(itinerary.getId()));
                                chatRoomRepository.deleteById(roomId);
                                return null;
                        });

                        return new DeleteChatRoomResponse(roomId, true);
                }).subscribeOn(dbScheduler)
                                .onErrorMap(
                                                e -> !(e instanceof ResponseStatusException),
                                                e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete chat room.")
                                );
        }

        @Override
        public Mono<UpdateChatRoomNameResponse> updateChatRoomName(String clerkId, UUID roomId, String name) {
                return Mono.fromCallable(() -> {
                        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found."));

                        if (!chatRoom.getClerkId().equals(clerkId)) {
                                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                                "You do not have permission to update this chat room.");
                        }

                        chatRoom.updateName(name);
                        ChatRoom saved = chatRoomRepository.save(chatRoom);

                        return new UpdateChatRoomNameResponse(saved.getId(), saved.getName(), saved.getUpdatedAt());
                }).subscribeOn(dbScheduler)
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
