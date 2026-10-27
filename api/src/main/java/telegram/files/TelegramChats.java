package telegram.files;

import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.drinkless.tdlib.TdApi;

import java.util.List;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class TelegramChats {
    private static final Log log = LogFactory.get();

    private final TelegramGateway client;

    private final ConcurrentMap<Long, TdApi.Chat> chats = new ConcurrentHashMap<>();

    private final ConcurrentMap<Long, TdApi.Supergroup> supergroups = new ConcurrentHashMap<>();

    private final NavigableSet<OrderedChat> mainChatList = new TreeSet<>();

    private final NavigableSet<OrderedChat> archivedChatList = new TreeSet<>();

    private boolean haveFullMainChatList = false;

    private Future<Void> loading;

    private boolean haveFullArchivedChatList = false;

    public TelegramChats(TelegramGateway client) {
        this.client = client;
    }

    public List<TdApi.Chat> getChatList(Long activatedChatId, String query, int limit, boolean archived) {
        List<TdApi.Chat> chatList = snapshot(archived).stream()
                .map(OrderedChat::chatId)
                .map(chats::get)
                .filter(Objects::nonNull)
                .filter(chat -> matches(chat, query))
                .limit(limit)
                .collect(Collectors.toList());

        if (activatedChatId != null) {
            TdApi.Chat activatedChat = chats.get(activatedChatId);
            if (activatedChat != null && !chatList.contains(activatedChat)) {
                chatList.addFirst(activatedChat);
            }
        }

        return chatList;
    }

    private List<OrderedChat> snapshot(boolean archived) {
        synchronized (chats) { return new ArrayList<>(archived ? archivedChatList : mainChatList); }
    }

    private static boolean matches(TdApi.Chat chat, String query) {
        if (StrUtil.isBlank(query)) return true;
        String q = query.strip().toLowerCase(Locale.ROOT);
        return String.valueOf(chat.id).contains(q) || java.util.Objects.toString(chat.title, "").toLowerCase(Locale.ROOT).contains(q);
    }

    public Future<Void> ensureLoaded(boolean refresh) {
        synchronized (chats) {
            if (loading != null && !loading.isComplete()) return loading;
            if (!refresh && haveFullMainChatList && haveFullArchivedChatList) return Future.succeededFuture();
            loading = loadList(false, 0).compose(_ -> loadList(true, 0));
            return loading;
        }
    }

    private Future<Void> loadList(boolean archived, int page) {
        if (page > 500) return Future.failedFuture("Channel list load exceeded its page budget");
        return client.execute(new TdApi.LoadChats(archived ? new TdApi.ChatListArchive() : new TdApi.ChatListMain(), 100))
            .timeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .compose(_ -> loadList(archived, page + 1))
            .recover(error -> {
                if (error instanceof TelegramRunException telegram && telegram.getError().code == 404) {
                    synchronized (chats) {
                        if (archived) haveFullArchivedChatList = true;
                        else haveFullMainChatList = true;
                    }
                    return Future.succeededFuture();
                }
                return Future.failedFuture(error);
            });
    }

    public Future<List<TdApi.Chat>> search(Long selected, String query, String scope, int limit, int offset, boolean refresh) {
        return ensureLoaded(refresh).compose(_ -> {
            Map<Long,TdApi.Chat> found = new LinkedHashMap<>();
            if (!"archive".equals(scope)) getChatList(null, query, Integer.MAX_VALUE, false).forEach(chat -> found.put(chat.id, chat));
            if (!"main".equals(scope)) getChatList(null, query, Integer.MAX_VALUE, true).forEach(chat -> found.put(chat.id, chat));
            Future<Void> lookup = Future.succeededFuture();
            if (StrUtil.isNotBlank(query)) lookup = client.execute(new TdApi.SearchChatsOnServer(query.strip(), null, 100))
                .compose(result -> {
                    List<Future<?>> details = new ArrayList<>();
                    for (long id : result.chatIds) details.add(client.execute(new TdApi.GetChat(id)).onSuccess(chat -> {
                        boolean include = "all".equals(scope) || java.util.Arrays.stream(chat.positions).anyMatch(position ->
                            "archive".equals(scope) ? position.list instanceof TdApi.ChatListArchive : position.list instanceof TdApi.ChatListMain);
                        if (include) synchronized (found) { found.put(id, chat); }
                    }));
                    return Future.all(details).<Void>mapEmpty();
                }).recover(error -> found.isEmpty() ? Future.failedFuture(error) : Future.succeededFuture());
            return lookup.map(_ -> {
                List<TdApi.Chat> result = new ArrayList<>(found.values());
                TdApi.Chat active = selected == null ? null : chats.get(selected);
                if (offset == 0 && active != null && !found.containsKey(selected)) result.addFirst(active);
                return result.stream().skip(Math.max(0, offset)).limit(Math.max(1, Math.min(5000,limit))).toList();
            });
        });
    }

    public TdApi.Chat getChat(long chatId) {
        return chats.get(chatId);
    }

    public boolean isForum(long chatId) {
        TdApi.Chat chat = chats.get(chatId);
        if (chat == null || !(chat.type instanceof TdApi.ChatTypeSupergroup type) || type.isChannel) {
            return false;
        }
        TdApi.Supergroup supergroup = supergroups.get(type.supergroupId);
        return supergroup != null && supergroup.isForum;
    }

    public void loadMainChatList() {
        synchronized (mainChatList) {
            if (!haveFullMainChatList) {
                // send LoadChats request if there are some unknown chats and have not enough known chats
                client.execute(new TdApi.LoadChats(new TdApi.ChatListMain(), 100))
                        .onSuccess(_ -> {
                            // chats had already been received through updates, let's retry request
                            loadMainChatList();
                        })
                        .onFailure(error -> {
                            if (((TelegramRunException) error).getError().code == 404) {
                                synchronized (mainChatList) {
                                    haveFullMainChatList = true;
                                    log.debug("Main chat list is loaded, size: %d".formatted(mainChatList.size()));
                                }
                            }
                        });
            }
        }
    }

    public void loadArchivedChatList() {
        synchronized (archivedChatList) {
            if (!haveFullArchivedChatList) {
                // send LoadChats request if there are some unknown chats and have not enough known chats
                client.execute(new TdApi.LoadChats(new TdApi.ChatListArchive(), 100))
                        .onSuccess(_ -> {
                            // chats had already been received through updates, let's retry request
                            loadArchivedChatList();
                        })
                        .onFailure(error -> {
                            if (((TelegramRunException) error).getError().code == 404) {
                                synchronized (archivedChatList) {
                                    haveFullArchivedChatList = true;
                                    log.debug("Archived chat list is loaded, size: %d".formatted(archivedChatList.size()));
                                }
                            }
                        });
            }
        }
    }

    public void onChatUpdated(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.UpdateNewChat.CONSTRUCTOR: {
                TdApi.UpdateNewChat updateNewChat = (TdApi.UpdateNewChat) object;
                TdApi.Chat chat = updateNewChat.chat;
                synchronized (chat) {
                    chats.put(chat.id, chat);

                    TdApi.ChatPosition[] positions = chat.positions;
                    setChatPositions(chat, positions);
                }
                break;
            }
            case TdApi.UpdateChatTitle.CONSTRUCTOR: {
                TdApi.UpdateChatTitle updateChat = (TdApi.UpdateChatTitle) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                if (chat == null) break;
                synchronized (chat) {
                    chat.title = updateChat.title;
                }
                break;
            }
            case TdApi.UpdateChatPhoto.CONSTRUCTOR: {
                TdApi.UpdateChatPhoto updateChat = (TdApi.UpdateChatPhoto) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                if (chat == null) break;
                synchronized (chat) {
                    chat.photo = updateChat.photo;
                }
                break;
            }
            case TdApi.UpdateChatReadInbox.CONSTRUCTOR: {
                TdApi.UpdateChatReadInbox updateChat = (TdApi.UpdateChatReadInbox) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                if (chat == null) break;
                synchronized (chat) {
                    chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId;
                    chat.unreadCount = updateChat.unreadCount;
                }
                break;
            }
            case TdApi.UpdateChatLastMessage.CONSTRUCTOR: {
                TdApi.UpdateChatLastMessage updateChat = (TdApi.UpdateChatLastMessage) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                if (chat == null) break;
                synchronized (chat) {
                    chat.lastMessage = updateChat.lastMessage;
                    setChatPositions(chat, updateChat.positions);
                }
                break;
            }
            case TdApi.UpdateChatPosition.CONSTRUCTOR: {
                TdApi.UpdateChatPosition updateChat = (TdApi.UpdateChatPosition) object;
                if (updateChat.position.list.getConstructor() != TdApi.ChatListMain.CONSTRUCTOR
                    && updateChat.position.list.getConstructor() != TdApi.ChatListArchive.CONSTRUCTOR) {
                    break;
                }

                TdApi.Chat chat = chats.get(updateChat.chatId);
                if (chat == null) break;
                synchronized (chat) {
                    int i;
                    for (i = 0; i < chat.positions.length; i++) {
                        if (chat.positions[i].list.getConstructor() == updateChat.position.list.getConstructor()) {
                            break;
                        }
                    }
                    TdApi.ChatPosition[] new_positions = new TdApi.ChatPosition[chat.positions.length + (updateChat.position.order == 0 ? 0 : 1) - (i < chat.positions.length ? 1 : 0)];
                    int pos = 0;
                    if (updateChat.position.order != 0) {
                        new_positions[pos++] = updateChat.position;
                    }
                    for (int j = 0; j < chat.positions.length; j++) {
                        if (j != i) {
                            new_positions[pos++] = chat.positions[j];
                        }
                    }
                    assert pos == new_positions.length;

                    setChatPositions(chat, new_positions);
                }
                break;
            }
            case TdApi.UpdateSupergroup.CONSTRUCTOR: {
                TdApi.UpdateSupergroup update = (TdApi.UpdateSupergroup) object;
                if (update.supergroup != null) {
                    supergroups.put(update.supergroup.id, update.supergroup);
                }
                break;
            }
        }
    }

    private void setChatPositions(TdApi.Chat chat, TdApi.ChatPosition[] positions) {
        synchronized (chat) {
            synchronized (chats) {
                for (TdApi.ChatPosition position : chat.positions) {
                    if (position.list.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
                        if (!mainChatList.remove(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was not found in mainChatList".formatted(chat.id));
                        }
                    }
                    if (position.list.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR) {
                        if (!archivedChatList.remove(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was not found in archivedChatList".formatted(chat.id));
                        }
                    }
                }

                chat.positions = positions;

                for (TdApi.ChatPosition position : chat.positions) {
                    if (position.list.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
                        if (!mainChatList.add(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was already in mainChatList".formatted(chat.id));
                        }
                    }
                    if (position.list.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR) {
                        if (!archivedChatList.add(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was already in archivedChatList".formatted(chat.id));
                        }
                    }
                }
            }
        }
    }

    private record OrderedChat(long chatId, TdApi.ChatPosition position) implements Comparable<OrderedChat> {

        @Override
        public int compareTo(OrderedChat o) {
            if (this.position.order != o.position.order) {
                return o.position.order < this.position.order ? -1 : 1;
            }
            if (this.chatId != o.chatId) {
                return o.chatId < this.chatId ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof OrderedChat(long id, TdApi.ChatPosition position1)) {
                return this.chatId == id && this.position.order == position1.order;
            }
            return false;
        }
    }
}
