package edu;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


public class ClientHandler implements Runnable{
        private static final Set<PrintWriter> clientWriters = new HashSet<>(); //This is just a list of all currently connected devices. This is just to test out broadcast.
        private static final Map<PrintWriter, String> clientUsernames = new HashMap<>(); //Map to keep track of which writer belongs to which username.
        private static final Map<String, ClientConnection> clients = new HashMap<>();
        private Socket client;
        private Connection conn;
        private PrintWriter out; 
        private String currentUsername = null;
        private boolean isLoggedIn = false;         

        private static final String USERS_DB_URL = "jdbc:sqlite:users.db";

        public ClientHandler(Socket client, Connection conn){
            this.client = client;
            this.conn = conn;
        }

        class ClientConnection {
            Socket socket;
            PrintWriter writer;
            DataOutputStream dataOut;
            String username;

            ClientConnection(Socket socket) throws IOException {
                this.socket = socket;
                this.writer = new PrintWriter(socket.getOutputStream(), true);
                this.dataOut = new DataOutputStream(socket.getOutputStream());
            }
        }

        @Override
        public void run(){
            try{
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream()));

                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                
                this.out = new PrintWriter(client.getOutputStream(), true);

                synchronized (clientWriters){
                    clientWriters.add(this.out);
                    System.out.println("Client connected. Total clients for broadcast: " + clientWriters.size());
                }

                String senderIP = client.getInetAddress().getHostAddress();
                String senderHost = client.getInetAddress().getHostName();

                String line;

                while ((line = in.readLine()) != null){
                    System.out.println("Received: " + line);

                    String[] parts = line.trim().split("\\s+");

                    if (parts.length == 0)
                        continue;

                    String command = parts[0];
                    System.out.println("Parsed command: '" + command + "'");
                    
                    switch (command){
                        case "login" -> handleLogin(parts, out, senderIP, senderHost);
                        case "register" -> handleRegister(parts, out, senderIP, senderHost);
                        case "send" -> handleSendUnified(parts, out, senderIP, senderHost);
                        case "createTask" -> createTasks(parts, out, senderIP, senderHost);
                        case "assignTask" -> assignTasks(parts, out);
                        case "viewTasks" -> viewTasks(parts, out);
                        case "sendFile" -> handleFileSend(parts, client.getInputStream(), client.getOutputStream());
                        default -> {
                            System.out.println("ERROR: Unknown command: '" + command + "'");
                            out.println("ERROR: Unknown command: '" + command + "'. Available: login, register, send, createTask, assignTask, viewTasks, sendFile");
                        }
                    }
                }

                
            } catch (Exception e){
                e.printStackTrace();
            } finally {
                if (this.out != null) {
                    synchronized (clientWriters) {
                        clientWriters.remove(this.out);
                        System.out.println("Client disconnected. Remaining clients for broadcast: " + clientWriters.size());
                    }
                }
                // Remove from clients map
                if (currentUsername != null) {
                    synchronized (clients) {
                        clients.remove(currentUsername);
                    }
                }
                try { client.close(); } catch (IOException ignored) {}
        }
        }

        private void handleLogin(String[] parts, PrintWriter out, String senderIP, String senderHost) {

            if (parts.length < 3){
                out.println("ERROR: usage: login <username> <password>");
                return;
            }

            String sql = """
                    SELECT 1 FROM users
                    WHERE username = ? AND password = ?
                    AND ip_address = ? AND hostname = ?
                    """;

            try (Connection conn = DriverManager.getConnection(USERS_DB_URL); 
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, parts[1]);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()){
                    String username = parts[1];
                    synchronized (clientUsernames){
                        clientUsernames.put(this.out, username);
                    }
                    currentUsername = username;
                        
                        // Register client in clients map for file transfer
                        try {
                            ClientConnection cc = new ClientConnection(client);
                            cc.username = username;
                            synchronized (clients) {
                                clients.put(username, cc);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        
                    out.println("LOGIN OK");
                    isLoggedIn = true;

                        String updateLoginSQL = "UPDATE users SET loggedin = 1 WHERE username = ?";
                    try (PreparedStatement updatePstmt = conn.prepareStatement(updateLoginSQL)){
                        updatePstmt.setString(1, username);
                        updatePstmt.executeUpdate();
                    } catch (SQLException e){
                        e.printStackTrace();
                    }
                } else {
                    out.println("LOGIN FAILED");        
                    }
                }
            } catch (SQLException e){
                out.println("LOGIN ERROR");
                e.printStackTrace();
            }
        }

        private void handleRegister(String[] parts, PrintWriter out, String senderIP, String senderHost){

            if (parts.length < 3){
                out.println("ERROR: usage: register <username> <password>");
                return;
            }

            String sql = """
                    INSERT INTO users (username, password, ip_address, hostname)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)){
                pstmt.setString(1, parts[1]);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                pstmt.executeUpdate();
                out.println("REGISTER OK");
            } catch (SQLException e){
                out.println("REGISTER FAILED");
                e.printStackTrace();
            }
        }


        private void handleSendUnified(String[] parts, PrintWriter out, String senderIP, String senderHost) {
            if (parts.length < 3) {
                out.println("ERROR: usage: send #<channel> <message> or send @<username> <message>");
                return;
            }

            String sender = currentUsername;
            if (sender == null) {
                out.println("ERROR: You must be logged in to send messages.");
                return;
            }

            String target = parts[1];

            // Parse message (everything after target)
            StringBuilder messageBuilder = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) messageBuilder.append(" ");
                messageBuilder.append(parts[i]);
            }
            String message = messageBuilder.toString().trim();

            if (message.isEmpty()) {
                 out.println("ERROR: Message content cannot be empty.");
            return;
            }

            if (message.length() > 2000) {
                out.println("ERROR: Message too long. Maximum length is 2000 characters.");
                return;
            }

            // Check if target starts with # (channel) or @ (user)
            if (target.startsWith("#")) {
                // Channel message
                String channel = target.substring(1);
                if (channel.isEmpty()) {
                    out.println("ERROR: Channel name cannot be empty.");
                    return;
                }
                sendChannelMessage(sender, channel, message, out);
            } else if (target.startsWith("@")) {
                // Direct message
                String receiver = target.substring(1);
                if (receiver.isEmpty()) {
                    out.println("ERROR: Username cannot be empty.");
                    return;
                }
                sendDirectMessage(sender, receiver, message, out);
            } else {
                out.println("ERROR: Target must start with # (channel) or @ (username).");
                out.println("Example: send #general Hello or send @alice Hello");
            }
        }

        private void sendChannelMessage(String sender, String channel, String message, PrintWriter out) {
            String insertSql = "INSERT INTO channel_messages (sender, channel, message) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setString(1, sender);
                pstmt.setString(2, channel);
                pstmt.setString(3, message);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                out.println("ERROR: Failed to save message to database.");
                e.printStackTrace();
                return;
            }

            String broadcastMessage = String.format("MSG #%s: %s", channel, message);
            synchronized (clientWriters) {
                for (PrintWriter writer : clientWriters) {
                writer.println(broadcastMessage);
                }
            }
            out.println("MESSAGE SENT");
        }

        private void sendDirectMessage(String sender, String receiver, String message, PrintWriter out) {
            String insertSql = "INSERT INTO direct_messages (sender, receiver, message) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setString(1, sender);
                pstmt.setString(2, receiver);
                pstmt.setString(3, message);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                out.println("ERROR: Failed to save message to database.");
                e.printStackTrace();
                return;
            }

            boolean receiverFound = false;
            synchronized (clientWriters) {
                synchronized (clientUsernames) {
                    for (Map.Entry<PrintWriter, String> entry : clientUsernames.entrySet()) {
                        if (entry.getValue().equals(receiver)) {
                            entry.getKey().println(String.format("receivedMessage %s \"%s\"", sender, message));
                            receiverFound = true;
                            break;
                        }
                    }
                }
            }

            if (receiverFound) {
                out.println("MESSAGE SENT");
            } else {
                out.println("MESSAGE SENT (User " + receiver + " is offline. Message will be delivered when they login.)");
            }
        }


        private void createTasks(String[] parts, PrintWriter out, String senderIP, String senderHost) {
            if (!checkLoggedIn(out)) return;
            
            if (parts.length < 2) {
                out.println("ERROR: usage: createTask <task_description>");
                return;
            }

            StringBuilder taskDescriptionBuilder = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) taskDescriptionBuilder.append(" ");
                taskDescriptionBuilder.append(parts[i]);
            }

            String taskDescription = taskDescriptionBuilder.toString().trim();

            if (taskDescription.isEmpty()) {
                out.println("ERROR: Task description cannot be empty.");
                return;
            }

            if (taskDescription.length() > 2000) {
                out.println("ERROR: Task description too long. Maximum length is 2000 characters.");
                return;
            }

            String insertSQL = "INSERT INTO tasks (creator, description) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                pstmt.setString(1, currentUsername);
                pstmt.setString(2, taskDescription);
                pstmt.executeUpdate();
                out.println("TASK CREATED");
            } catch (SQLException e) {
                out.println("ERROR: Failed to create task.");
                e.printStackTrace();
            }
        }


        private void assignTasks(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;
            
            if (parts.length < 3) {
                out.println("ERROR: usage: assignTask <task_id> <username>");
                return;
            }

            int taskId;
            try {
                taskId = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                out.println("ERROR: Task ID must be a number.");
                return;
            }

            String assignee = parts[2];

            // Check if task exists
            String checkTaskSql = "SELECT id FROM tasks WHERE id = ?";
            try (PreparedStatement checkPstmt = conn.prepareStatement(checkTaskSql)) {
                checkPstmt.setInt(1, taskId);
                try (ResultSet rs = checkPstmt.executeQuery()) {
                    if (!rs.next()) {
                        out.println("ERROR: Task with ID " + taskId + " not found.");
                        return;
                    }
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to check task.");
                e.printStackTrace();
                return;
            }

            // Check if user exists
            String checkUserSql = "SELECT username FROM users WHERE username = ?";
            try (PreparedStatement checkPstmt = conn.prepareStatement(checkUserSql)) {
                checkPstmt.setString(1, assignee);
                try (ResultSet rs = checkPstmt.executeQuery()) {
                    if (!rs.next()) {
                        out.println("ERROR: User '" + assignee + "' not found.");
                        return;
                    }
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to check user.");
                e.printStackTrace();
                return;
            }

            // Assign task
            String assignSql = "UPDATE tasks SET assignee = ? WHERE id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(assignSql)) {
                pstmt.setString(1, assignee);
                pstmt.setInt(2, taskId);
                int rowsUpdated = pstmt.executeUpdate();
                if (rowsUpdated > 0) {
                    out.println("TASK ASSIGNED: Task #" + taskId + " assigned to " + assignee);
                } else {
                    out.println("ERROR: Failed to assign task.");
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to assign task.");
                e.printStackTrace();
            }
        }

        private void viewTasks(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;

            String sql = "SELECT id, creator, assignee, description FROM tasks ORDER BY id";
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                
                boolean hasTasks = false;
                out.println("=== TASKS ===");
                while (rs.next()) {
                    hasTasks = true;
                    int id = rs.getInt("id");
                    String creator = rs.getString("creator");
                    String assignee = rs.getString("assignee");
                    String description = rs.getString("description");
                    
                    out.print("Task #" + id + ": " + description);
                    out.print(" [Creator: " + creator + "]");
                    if (assignee != null && !assignee.isEmpty()) {
                        out.print(" [Assigned to: " + assignee + "]");
                    } else {
                        out.print(" [Unassigned]");
                    }
                    out.println();
                }
                
                if (!hasTasks) {
                    out.println("No tasks found.");
                }
                out.println("============");
            } catch (SQLException e) {
                out.println("ERROR: Failed to retrieve tasks.");
                e.printStackTrace();
            }
        }
        
        private boolean checkLoggedIn(PrintWriter out) {
            if (currentUsername == null) {
                out.println("ERROR: You must be logged in.");
                return false;
            }
            return true;
        }

        private void handleFileSend(String[] parts, InputStream rawIn, OutputStream rawOut) {
            
            DataInputStream fileDataIn = null;
            DataOutputStream fileDataOut = null;
            try {
                fileDataOut = new DataOutputStream(rawOut);
                
                if (!isLoggedIn) {
                    fileDataOut.writeUTF("ERROR: Login required");
                    return;
                }

                if (parts.length < 5) {
                    fileDataOut.writeUTF("ERROR: sendFile <channel|user> <destination> <filename> <filesize>");
                    return;
                }

                String type = parts[1]; // channel | user
                String destination = parts[2];
                String filename = parts[3];
                long fileSize = Long.parseLong(parts[4]);

                this.out.println("READY_FOR_FILE");

                fileDataIn = new DataInputStream(rawIn);
                byte[] fileBytes = new byte[(int) fileSize];
                fileDataIn.readFully(fileBytes);

            
                String sql = """
                    INSERT INTO files (sender, destination_type, destination_name, filename, file_data)
                    VALUES (?, ?, ?, ?, ?)
                """;

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, currentUsername);
                    pstmt.setString(2, type.toUpperCase());
                    pstmt.setString(3, destination);
                    pstmt.setString(4, filename);
                    pstmt.setBytes(5, fileBytes);
                    pstmt.executeUpdate();
                }

            
                forwardFile(type, destination, filename, fileBytes);

                fileDataOut.writeUTF("FILE SENT");

            } catch (Exception e) {
                e.printStackTrace();
                try {
                    if (fileDataOut != null) {
                        fileDataOut.writeUTF("ERROR: File transfer failed");
                    }
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            } 
    }

    private void forwardFile(String type, String destination, String filename, byte[] data) throws IOException {
        synchronized (clients) {

            if (type.equalsIgnoreCase("user")) {
                ClientConnection cc = clients.get(destination);
                if (cc != null) {
                    cc.dataOut.writeUTF("incomingFile " + filename + " " + data.length);
                    cc.dataOut.write(data);
                    cc.dataOut.flush();
                }
            }

            else if (type.equalsIgnoreCase("channel")) {
                for (ClientConnection cc : clients.values()) {
                    cc.dataOut.writeUTF("incomingFile " + filename + " " + data.length);
                    cc.dataOut.write(data);
                    cc.dataOut.flush();
                }
            }
        }
    }


}
