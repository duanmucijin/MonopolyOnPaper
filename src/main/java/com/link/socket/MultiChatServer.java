package com.link.socket;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;

public class MultiChatServer extends org.java_websocket.server.WebSocketServer {
    private Map<WebSocket, JSONObject> userConnections = new HashMap<>();
    private Map<WebSocket, String> clientInfo;  // 记录客户端信息，WebSocket对象与客户端标识的映射
    private List<ChatRoom> chatRooms;  // 房间列表，存储多个聊天室
    private int currentRoomIndex;  // 当前房间索引
    private Set<String> joinedPlayIds = new HashSet<>();//已加入房间的playId 防止重复加入


    public MultiChatServer(int port) throws UnknownHostException {
        super(new InetSocketAddress(port));
        clientInfo = new HashMap<>();
        chatRooms = new ArrayList<>();
        currentRoomIndex = -1;
    }

    // ...

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientIdentifier = generateClientIdentifier();
        clientInfo.put(conn, clientIdentifier);

        // 发送初始状态给客户端
        sendInitialStatus(conn);

        System.out.println(clientIdentifier + " entered the server.");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientIdentifier = clientInfo.get(conn);
        clientInfo.remove(conn);

        // 获取房间
        ChatRoom chatRoom = getChatRoomByWebSocket(conn);
        if (chatRoom != null) {
            // 从joinedPlayIds集合中移除离开的客户端的playId
            String playId = chatRoom.getMemberPlayId(conn);
            if (playId != null) {
                joinedPlayIds.remove(playId);
            }

            chatRoom.removeMember(conn);

            // 更新房间状态或关闭房间
            if (chatRoom.members.isEmpty()) {
                // 房间内没有成员了，关闭房间
                chatRooms.remove(chatRoom.getRoomId());
            } else {
                // 房间仍有成员，更新房间信息并广播给其他成员
                JSONObject roomInfoMessage = createRoomInfoMessage(chatRoom);
                broadcastToRoomMembers(chatRoom, roomInfoMessage);
            }

            // 构建房间信息消息
            JSONObject roomInfoMessage = createRoomInfoMessage(chatRoom);
            broadcastToRoomMembers(chatRoom, roomInfoMessage);
        }

        System.out.println(clientIdentifier + " has left the server.");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String clientIdentifier = clientInfo.get(conn);
        JSONObject json = new JSONObject(message);
        String status = json.getString("status");

        switch (status) {
            case "Link"://连接服务器后发送好友列表
                handleUserLink(conn, json);
                break;
            case "InviteShip"://邀请添加好友
                handleFriendInvitation(conn, json);
                break;
            case "InviteShipBack":
                handleInviteShipBack(conn, json);
                break;
            case "RemoveShip"://删除好友
                handleRemoveFriend(conn, json);
                break;
            case "AlongWHGame"://邀请加入对局
                handleInviteToRoom(conn, json);
                break;
            case "AlongWHGameDeci"://邀请加入对局
                handleAlongWHGameDeci(conn, json);
                break;
            case "CancelRoom"://删除房间
                handleCancelRoom(conn, json);
                break;
            case "IdSend"://以下为game相关
                handleIdSendStatus(conn, json);
                break;
            case "MemberPlayStep":
                handleMemberPlayStep(conn, json);
                break;
            default:
                System.out.println("Received unknown status from " + clientIdentifier);
                break;
        }
    }

    private void sendInitialStatus(WebSocket conn) {
        JSONObject json = new JSONObject();
        json.put("status", "IdSend");
        conn.send(json.toString());
    }

    // 处理Link操作
    private void handleUserLink(WebSocket conn, JSONObject json) {
        String userId = json.getString("userId");
        JSONArray friendList = json.getJSONArray("friendList");

        // 将用户连接信息存储到userConnections
        JSONObject userConnectionInfo = new JSONObject();
        userConnectionInfo.put("userId", userId);
        userConnectionInfo.put("friendList", friendList);
        userConnections.put(conn, userConnectionInfo);
//
//        // 发送初始状态给客户端
//        sendInitialStatus(conn);

        // 向所有好友发送FriendKeepLine消息
        for (WebSocket friendConn : userConnections.keySet()) {
            if (friendConn != conn) {
                JSONObject friendKeepLineMsg = new JSONObject();
                friendKeepLineMsg.put("status", "FriendKeepLine");
                friendKeepLineMsg.put("userId", userId);
                friendKeepLineMsg.put("friendId", userConnections.get(friendConn).getString("userId"));
                friendConn.send(friendKeepLineMsg.toString());
            }
        }
    }

    private void handleFriendInvitation(WebSocket conn, JSONObject json) {
        String userId = json.getString("userId");
        String invitedUserId = json.getString("invitedUserId");

        // 获取发起邀请用户的信息
        JSONObject userConnectionInfo = userConnections.get(conn);
        if (userConnectionInfo == null) {
            // 未找到用户连接信息，可能用户未连接或连接信息已失效
            return;
        }

        // 获取好友列表
        JSONArray friendList = userConnectionInfo.getJSONArray("friendList");
        if (friendList == null) {
            friendList = new JSONArray();
        }

        // 检查被邀请用户是否已经是好友
        if (isFriend(userId, invitedUserId, friendList)) {
            // 被邀请用户已经是好友，返回错误信息给发起邀请用户
            JSONObject response = new JSONObject();
            response.put("status", "Error");
            response.put("message", "The invited user is already your friend.");
            conn.send(response.toString());
            return;
        }

        // 查找被邀请用户的连接
        WebSocket invitedUserConn = null;
        for (Map.Entry<WebSocket, JSONObject> entry : userConnections.entrySet()) {
            JSONObject invitedUserInfo = entry.getValue();
            String invitedUserIdInMap = invitedUserInfo.getString("userId");
            if (invitedUserIdInMap.equals(invitedUserId)) {
                invitedUserConn = entry.getKey();
                break;
            }
        }

        if (invitedUserConn == null) {
            // 未找到被邀请用户的连接，可能用户未连接或连接信息已失效
            // 在此可以处理未找到被邀请用户的情况，例如返回错误信息给发起邀请用户
            return;
        }

        // 转发邀请消息给被邀请用户
        invitedUserConn.send(json.toString());
    }

    private void handleInviteShipBack(WebSocket conn, JSONObject json) {
        String userId = json.getString("userId");
        String invitedUserId = json.getString("invitedUserId");
        String nickName = json.getString("nickName");

        // 获取邀请方用户的信息
        JSONObject userConnectionInfo = userConnections.get(conn);
        if (userConnectionInfo == null) {
            // 未找到用户连接信息，可能用户未连接或连接信息已失效
            return;
        }

        // 获取好友列表
        JSONArray friendList = userConnectionInfo.getJSONArray("friendList");
        if (friendList == null) {
            friendList = new JSONArray();
        }

        // 检查邀请方用户是否已经是好友
        if (isFriend(userId, invitedUserId, friendList)) {
            // 邀请方用户已经是好友，返回错误信息给被邀请用户
            JSONObject response = new JSONObject();
            response.put("status", "Error");
            response.put("message", "You are already friends with the inviting user.");
            conn.send(response.toString());
            return;
        }

        // 查找邀请方用户的连接
        WebSocket invitingUserConn = getWebSocketByUserId(userId);
        if (invitingUserConn == null) {
            // 未找到邀请方用户的连接，可能用户未连接或连接信息已失效
            // 在此可以处理未找到邀请方用户的情况，例如返回错误信息给被邀请用户
            return;
        }

        // 转发回复消息给邀请方用户
        invitingUserConn.send(json.toString());
    }

    private boolean isFriend(String userId, String invitedUserId, JSONArray friendList) {
        for (int i = 0; i < friendList.length(); i++) {
            JSONObject friendInfo = friendList.getJSONObject(i);
            String friendUserId = friendInfo.getString("userId");
            if (friendUserId.equals(invitedUserId)) {
                return true;
            }
        }
        return false;
    }

    private void handleRemoveFriend(WebSocket conn, JSONObject json) {
        String userId = json.getString("userId");
        String removeUserId = json.getString("removeUserId");

        // 获取发起删除好友用户的信息
        JSONObject userConnectionInfo = userConnections.get(conn);
        if (userConnectionInfo == null) {
            // 未找到用户连接信息，可能用户未连接或连接信息已失效
            return;
        }

        // 获取好友列表
        JSONArray friendList = userConnectionInfo.getJSONArray("friendList");
        if (friendList == null) {
            friendList = new JSONArray();
        }

        // 检查被删除用户是否已经是好友
        if (!isFriend(userId, removeUserId, friendList)) {
            // 被删除用户不是好友，返回错误信息给发起删除好友用户
            JSONObject response = new JSONObject();
            response.put("status", "Error");
            response.put("message", "The user to be removed is not your friend.");
            conn.send(response.toString());
            return;
        }

        // 查找被删除用户的连接
        WebSocket removeUserConn = null;
        for (Map.Entry<WebSocket, JSONObject> entry : userConnections.entrySet()) {
            JSONObject removeUserInfo = entry.getValue();
            String removeUserIdInMap = removeUserInfo.getString("userId");
            if (removeUserIdInMap.equals(removeUserId)) {
                removeUserConn = entry.getKey();
                break;
            }
        }

        // 如果未找到被删除用户的连接，则直接返回
        if (removeUserConn == null) {
            return;
        }

        // 转发删除好友消息给被删除用户
        removeUserConn.send(json.toString());

        // 从好友列表中删除被删除用户
        for (int i = 0; i < friendList.length(); i++) {
            JSONObject friendInfo = friendList.getJSONObject(i);
            String friendUserId = friendInfo.getString("userId");
            if (friendUserId.equals(removeUserId)) {
                friendList.remove(i);
                break;
            }
        }

        // 更新好友列表信息
        userConnectionInfo.put("friendList", friendList);
    }

    private void handleInviteToRoom(WebSocket conn, JSONObject json) {
        String userId = json.getString("userId");
        String alongInvitedId = json.getString("alongInvitedId");

        // 获取发起邀请用户的信息
        JSONObject userConnectionInfo = userConnections.get(conn);
        if (userConnectionInfo == null) {
            // 未找到用户连接信息，可能用户未连接或连接信息已失效
            return;
        }

        // 获取好友列表
        JSONArray friendList = userConnectionInfo.getJSONArray("friendList");
        if (friendList == null) {
            friendList = new JSONArray();
        }

        // 检查被邀请用户是否已经是好友
        if (!isFriend(userId, alongInvitedId, friendList)) {
            // 被邀请用户不是好友，返回错误信息给发起邀请用户
            JSONObject response = new JSONObject();
            response.put("status", "Error");
            response.put("message", "The user to be invited is not your friend.");
            conn.send(response.toString());
            return;
        }

        // 查找被邀请用户的连接
        WebSocket alongInvitedUserConn = null;
        for (Map.Entry<WebSocket, JSONObject> entry : userConnections.entrySet()) {
            JSONObject alongInvitedUserInfo = entry.getValue();
            String alongInvitedUserIdInMap = alongInvitedUserInfo.getString("userId");
            if (alongInvitedUserIdInMap.equals(alongInvitedId)) {
                alongInvitedUserConn = entry.getKey();
                break;
            }
        }

        if (alongInvitedUserConn == null) {
            // 未找到被邀请用户的连接，可能用户未连接或连接信息已失效
            // 在此可以处理未找到被邀请用户的情况，例如返回错误信息给发起邀请用户
            return;
        }

        // 创建房间，并将房间信息发送给被邀请用户
        ChatRoom chatRoom = createChatRoom(generateRoomId());
        chatRoom.addMember(conn, userId, userConnectionInfo.getString("nickName"));

        JSONObject roomInfoMessage = new JSONObject();
        roomInfoMessage.put("status", "AlongWHGame");
        roomInfoMessage.put("userId", userId);
        roomInfoMessage.put("nickName", userConnectionInfo.getString("nickName"));
        roomInfoMessage.put("alongInvitedId", alongInvitedId);
        roomInfoMessage.put("roomId", chatRoom.getRoomId());
        alongInvitedUserConn.send(roomInfoMessage.toString());
    }

    private void handleCancelRoom(WebSocket conn, JSONObject json) {
        String userId = json.getString("userId");

        // 获取发起取消房间用户的信息
        JSONObject userConnectionInfo = userConnections.get(conn);
        if (userConnectionInfo == null) {
            // 未找到用户连接信息，可能用户未连接或连接信息已失效
            return;
        }

        // 判断用户是否为房主
        ChatRoom chatRoom = getChatRoomByWebSocket(conn);
        if (chatRoom != null && chatRoom.isHost(userId)) {
            // 关闭房间
            chatRooms.remove(chatRoom.getRoomId());

            // 广播房间已关闭消息给房间内其他成员
            JSONObject response = new JSONObject();
            response.put("status", "RoomClosed");
            broadcastToRoomMembers(chatRoom, response);
        }
    }

    private void handleAlongWHGameDeci(WebSocket conn, JSONObject json) {
        String decision = json.getString("decision");
        String alongInvitedId = json.getString("alonginvitedId");
        String nickName = json.getString("nickName");
        String roomId = json.getString("roomId");

        // 获取房间
        ChatRoom chatRoom = getChatRoomById(roomId);
        if (chatRoom == null) {
            // 房间不存在，返回错误信息给客户端或其他处理方式
            return;
        }

        if (decision.equals("recive")) {
            // 接受邀请
            // 创建新的WebSocket成员并加入房间
            WebSocket invitedWebSocket = getWebSocketByUserId(alongInvitedId);
            if (invitedWebSocket != null) {
                chatRoom.addMember(invitedWebSocket, alongInvitedId, nickName);
                // 广播新加入的玩家信息给房间内其他成员
                broadcastNewPlayerJoined(chatRoom, chatRoom.getMemberByWebSocket(invitedWebSocket));
            }
        } else if (decision.equals("refuse")) {
            // 拒绝邀请
            // 将拒绝信息发送给房间内房主
            Member host = chatRoom.getMembers().get(0); // 获取房间内第一个成员作为房主
            if (host != null) {
                WebSocket hostWebSocket = host.getWebSocket();
                if (hostWebSocket != null && hostWebSocket.isOpen()) {
                    JSONObject refuseMessage = new JSONObject();
                    refuseMessage.put("status", "AlongWHGameDeci");
                    refuseMessage.put("decision", "refuse");
                    refuseMessage.put("alonginvitedId", alongInvitedId);
                    refuseMessage.put("nickName", nickName);
                    hostWebSocket.send(refuseMessage.toString());
                }
            }
        }
    }

    public WebSocket getWebSocketByUserId(String userId) {
        for (Map.Entry<WebSocket, String> entry : clientInfo.entrySet()) {
            String playId = entry.getValue();
            if (playId.equals(userId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    //以下游戏相关
    private void handleIdSendStatus(WebSocket conn, JSONObject json) {
        String playId = json.getString("playId");
        String nickName = json.getString("nickName");

        //重新加入 获取房间号
        String roomId = json.optString("roomId");

        // 创建房间
        ChatRoom chatRoom;

        if (joinedPlayIds.contains(playId)) {
            // 重复加入房间的处理，例如返回错误信息给客户端
            return;
        }


        if (!roomId.isEmpty()) {

            // 如果roomId不为空字符串，根据指定的房间ID获取房间对象
            chatRoom = getChatRoomById(roomId);

            if (chatRoom == null) {
                // 房间不存在，返回错误信息给客户端或其他处理方式
                return;
            }

            if (!chatRoom.isJoinAllowed()) {
                // 房间不允许加入，返回错误信息给客户端或其他处理方式
                return;
            }

            // 移除先前的 WebSocket 实例（如果存在）
            chatRoom.removeMember(conn);

            // 加入房间
            chatRoom.addMember(conn, playId, nickName);

            // 将playId添加到已加入房间的列表中
            joinedPlayIds.add(playId);

            // 构建响应消息
            JSONObject response = createJoinSuccessResponse(chatRoom,conn);

            // 发送响应消息给客户端
            conn.send(response.toString());

            //广播新加入的客户端给房间内其他成员
            broadcastNewPlayerJoined(chatRoom, chatRoom.getMembers().get(0));

            if (chatRoom.getMembers().size() >= 4) {
                chatRoom.isGaming = true;
                JSONObject broadcastMsg = new JSONObject();
                broadcastMsg.put("status", "GBegin");
                String roomIdStr = Integer.toString(chatRoom.getRoomId());
                broadcastMsg.put("roomId", roomIdStr);
                broadcastToRoomMembers(chatRoom, broadcastMsg);
            }

            System.out.println(clientInfo.get(conn) + " joined chat room " + chatRoom.getRoomId());

        }else{
            chatRoom = getOrCreateChatRoom();

            // 移除先前的 WebSocket 实例（如果存在）
            chatRoom.removeMember(conn);

            // 加入房间
            chatRoom.addMember(conn, playId, nickName);

            // 将playId添加到已加入房间的列表中
            joinedPlayIds.add(playId);

            // 构建响应消息
            JSONObject response = createJoinSuccessResponse(chatRoom,conn);

            // 发送响应消息给客户端
            conn.send(response.toString());

            //广播新加入的客户端给房间内其他成员
            broadcastNewPlayerJoined(chatRoom, chatRoom.getMembers().get(0));

            if (chatRoom.getMembers().size() >= 4) {
                chatRoom.isGaming = true;
                JSONObject broadcastMsg = new JSONObject();
                broadcastMsg.put("status", "GBegin");
                String roomIdStr = Integer.toString(chatRoom.getRoomId());
                broadcastMsg.put("roomId", roomIdStr);
                broadcastToRoomMembers(chatRoom, broadcastMsg);
            }

            System.out.println(clientInfo.get(conn) + " joined chat room " + chatRoom.getRoomId());
        }



    }

    private void handleMemberPlayStep(WebSocket conn, JSONObject json) {
//        String playId = json.getString("playId");
//        String memberPlayStepEvent = json.getString("memberPlayStepEvent");
//
        // 查找客户端所在的房间
        ChatRoom chatRoom = getChatRoomByWebSocket(conn);
        if (chatRoom == null) {
            // 客户端不在任何房间中，忽略该消息
            return;
        }

        // 构建广播消息
//        JSONObject broadcastMsg = new JSONObject();
//        broadcastMsg.put("status", "MemberPlayStep");
//        broadcastMsg.put("playId", playId);
//        broadcastMsg.put("memberPlayStepEvent", memberPlayStepEvent);

        // 广播消息给房间内其他客户端
        broadcastToRoomMembersExceptSender(chatRoom, conn, json);
    }

    private JSONObject createJoinSuccessResponse(ChatRoom chatRoom , WebSocket conn) {
        JSONObject response = new JSONObject();
        response.put("status", "JinSuccess");
        response.put("Index", chatRoom.getMemberIndex(conn));
        response.put("roomMembers", chatRoom.getMemberInfo());
        return response;
    }

    private JSONObject createRoomInfoMessage(ChatRoom chatRoom) {
        JSONObject message = new JSONObject();
        message.put("status", "SoneLeave");
        message.put("roomMembers", chatRoom.getMemberInfo());

        return message;
    }

    private void broadcastNewPlayerJoined(ChatRoom chatRoom, Member newMember) {
        List<Member> roomMembers = chatRoom.getMembers();

        JSONObject message = new JSONObject();
        message.put("status", "JinNPlayer");

        JSONArray memberArray = new JSONArray();
        for (Member member : roomMembers) {
            JSONObject memberInfo = new JSONObject();
            memberInfo.put("playId", member.getPlayId());
            memberInfo.put("nickName", member.getNickName());
            memberArray.put(memberInfo);
        }
        message.put("roomMembers", memberArray);

        String broadcastMessage = message.toString();
        for (Member member : roomMembers) {
            WebSocket memberWebSocket = member.getWebSocket();
            memberWebSocket.send(broadcastMessage);
        }
    }

    //还需要处理发送消息失败、异常处理等情况。
    private void broadcastToRoomMembers(ChatRoom chatRoom, JSONObject message) {
        List<Member> roomMembers = chatRoom.getMembers();

        for (Member member : roomMembers) {
            member.webSocket.send(message.toString());
        }
    }

    private void broadcastToRoomMembersExceptSender(ChatRoom chatRoom, WebSocket conn, JSONObject message) {
        List<Member> roomMembers = chatRoom.getMembers();

        for (Member member : roomMembers) {
            WebSocket memberWebSocket = member.getWebSocket();
            if (memberWebSocket != null && memberWebSocket.isOpen() && memberWebSocket != conn) {
                member.getWebSocket().send(message.toString());
            }
        }
    }


    private ChatRoom getChatRoomById(String roomId) {
        int targetRoomId = Integer.parseInt(roomId);
        for (ChatRoom chatRoom : chatRooms) {
            if (chatRoom.getRoomId() == targetRoomId) {
                return chatRoom;
            }
        }
        return null; // 没有找到对应的房间
    }

    private ChatRoom createChatRoom(int roomId) {
        ChatRoom chatRoom = new ChatRoom(roomId);
        chatRooms.add(chatRoom);
        return chatRoom;
    }

    private class ChatRoom {
        private int roomId;
        private boolean isGaming; // 添加isGaming属性
        private List<Member> members;

        public ChatRoom(int roomId) {
            this.roomId = roomId;
            this.isGaming = false; // 默认房间游戏状态为false，即未进行游戏
            members = new ArrayList<>();
        }

        public int getRoomId() {
            return roomId;
        }

        public boolean isGaming() {
            return isGaming;
        }

        public void setGaming(boolean isGaming) {
            this.isGaming = isGaming;
        }

        // ChatRoom类内部的isHost方法
        public boolean isHost(String userId) {
            if (members.isEmpty()) {
                return false;
            }
            Member hostMember = members.get(0); // 假设房主是第一个加入房间的成员
            return hostMember.getPlayId().equals(userId);
        }

        public Member getMemberByWebSocket(WebSocket webSocket) {
            for (Member member : members) {
                if (member.getWebSocket() == webSocket) {
                    return member;
                }
            }
            return null;
        }

        // 获取房主的userId
        public String getHostId() {
            if (!members.isEmpty()) {
                return members.get(0).getPlayId();
            }
            return null;
        }

        public String getMemberPlayId(WebSocket conn) {
            for (Member member : members) {
                if (member.getWebSocket() == conn) {
                    return member.getPlayId();
                }
            }
            return null;
        }

        // 判断房间是否允许加入
        // ChatRoom类内部的isJoinAllowed方法
        public boolean isJoinAllowed() {
            return members.size() < 4; // 假设房间最多允许4名成员
        }

        public void addMember(WebSocket conn, String playId, String nickName) {
            Member member = new Member(conn, playId, nickName);
            members.add(member);
        }

        public void removeMember(WebSocket conn) {
            Iterator<Member> iterator = members.iterator();
            while (iterator.hasNext()) {
                Member member = iterator.next();
                if (member.getWebSocket() == conn) {
                    iterator.remove();
                    break;
                }
            }
        }

        public List<Member> getMembers() {
            return members;
        }

        public int getMemberIndex(WebSocket conn) {
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).getWebSocket() == conn) {
                    return i;
                }
            }
            return -1;
        }

        public JSONArray getMemberInfo() {
            JSONArray memberInfo = new JSONArray();
            for (Member member : members) {
                JSONObject info = new JSONObject();
                info.put("playId", member.getPlayId());
                info.put("nickName", member.getNickName());
                memberInfo.put(info);
            }
            return memberInfo;
        }
    }

    private class Member {
        private WebSocket webSocket;
        private String playId;
        private String nickName;

        public Member(WebSocket webSocket, String playId, String nickName) {
            this.webSocket = webSocket;
            this.playId = playId;
            this.nickName = nickName;
        }

        public WebSocket getWebSocket() {
            return webSocket;
        }

        public String getPlayId() {
            return playId;
        }

        public String getNickName() {
            return nickName;
        }
    }


    private ChatRoom getChatRoomByWebSocket(WebSocket conn) {
        for (ChatRoom chatRoom : chatRooms) {
            if (chatRoom.getMembers().stream().anyMatch(member -> member.getWebSocket() == conn)) {
                return chatRoom;
            }
        }
        return null;
    }

    private ChatRoom getOrCreateChatRoom() {
        if (chatRooms.isEmpty() || chatRooms.get(chatRooms.size() - 1).getMembers().size() >= 4 || chatRooms.get(chatRooms.size() - 1).isGaming()) {
            int newRoomId = generateRoomId();
            ChatRoom newChatRoom = createChatRoom(newRoomId);
            return newChatRoom;
        } else {
            return chatRooms.get(chatRooms.size() - 1);
        }
    }


    private int generateRoomId() {
        // Generate a random room ID (you can customize the logic here)
        Random random = new Random();
        return random.nextInt(10000);
    }


    private String generateClientIdentifier() {
        return "Client" + (clientInfo.size() + 1);
    }


    private void joinChatRoom(WebSocket conn, List<WebSocket> chatRoom) {
        chatRoom.add(conn);
    }

    private void removeClientFromChatRoom(WebSocket conn, List<WebSocket> chatRoom) {
        chatRoom.remove(conn);

        if (chatRoom.isEmpty()) {
            chatRooms.remove(chatRoom);
            currentRoomIndex--;
        }
    }

    private void broadcastToChatRoom(WebSocket sender, List<WebSocket> chatRoom, String message) {
        for (WebSocket client : chatRoom) {
            if (client != sender) {
                client.send(message);
            }
        }
    }


    public static void main(String[] args) throws InterruptedException, IOException {
        int port = 8887;
        try {
            port = Integer.parseInt(args[0]);
        } catch (Exception ex) {
        }
        MultiChatServer server = new MultiChatServer(port);
        server.start();
        System.out.println("MultiChatServer started on port: " + server.getPort());

        BufferedReader sysin = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            if (sysin.ready()) {//防止 BufferedReader readLine
                String in = sysin.readLine();
                server.broadcast(in);
                if (in.equals("exit")) {
                    server.stop(1000);
                    break;
                }
            } else {
                // 执行其他逻辑，或者可以添加适当的延迟
                Thread.sleep(100);
            }
        }
        // ...
    }

    //发生错误时调用。 如果错误导致 websocket 连接失败onClose(WebSocket, int, String, boolean)将被额外调用。 此方法将主要因为 IO 或协议错误而被调用
    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
        if (conn != null) {
        }
    }

    //服务器启动成功的时候执行该操作

    @Override
    public void onStart() {
        System.out.println("Server started!");
        //用于丢失连接的间隔检查的设置器 值小于或等于 0 会导致检查被停用 单位为s
        setConnectionLostTimeout(100);
    }
}


