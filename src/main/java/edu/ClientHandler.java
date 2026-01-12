package edu;

/**
 * ClientHandler: Handles individual client connections and processes client commands.
 * Manages authentication, messaging, task management, and file transfers for each connected client.
 */

import java.io.*;
import java.net.*;
import java.sql.*;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

        public ClientHandler(Socket client){
            this.client = client;
            // Each ClientHandler gets its own connection to avoid SQLite threading issues
            try {
                this.conn = DriverManager.getConnection(USERS_DB_URL);
            } catch (SQLException e) {
                System.err.println("Error creating database connection: " + e.getMessage());
                e.printStackTrace();
            }
        }

        class ClientConnection {
            Socket socket;
            PrintWriter writer;
            String username;

            ClientConnection(Socket socket) throws IOException {
                this.socket = socket;
                this.writer = new PrintWriter(socket.getOutputStream(), true);
            }
        }

        @Override
        public void run(){
            // Check if database connection was successfully created
            if (conn == null) {
                System.err.println("Failed to create database connection. Closing client connection.");
                try {
                    client.close();
                } catch (IOException e) {
                    // Ignore
                }
                return;
            }
            
            try{
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream()));

                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                
                this.out = out; // Use the same PrintWriter instance

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
                        case "viewTasks" -> viewTasks(parts, out);
                        case "updateTask" -> updateTask(parts, out);
                        case "deleteTask" -> deleteTask(parts, out);
                        case "viewDirectMessages" -> viewDirectMessages(parts, out);
                        case "viewChannelMessages" -> viewChannelMessages(parts, out);
                        case "sendFile" -> handleFileSend(parts, in);
                        default -> {
                            System.out.println("ERROR: Unknown command: '" + command + "'");
                            out.println("ERROR: Unknown command: '" + command + "'. Available: login, register, send, createTask, viewTasks, updateTask, deleteTask, viewDirectMessages, viewChannelMessages, sendFile");
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
                // Close database connection
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        System.err.println("Error closing database connection: " + e.getMessage());
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

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
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

        // Helper method to save message to database
        private boolean saveMessageToDatabase(String table, String[] columns, String[] values, PrintWriter out) {
            // Add timestamp column if it exists in the table
            boolean hasTimestamp = false;
            for (String col : columns) {
                if (col.equals("timestamp")) {
                    hasTimestamp = true;
                    break;
                }
            }
            
            StringBuilder sqlBuilder = new StringBuilder("INSERT INTO ").append(table).append(" (");
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) sqlBuilder.append(", ");
                sqlBuilder.append(columns[i]);
            }
            // Add timestamp if table has timestamp column but it's not in the columns array
            if (!hasTimestamp && (table.equals("direct_messages") || table.equals("channel_messages"))) {
                sqlBuilder.append(", timestamp");
            }
            sqlBuilder.append(") VALUES (");
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) sqlBuilder.append(", ");
                sqlBuilder.append("?");
            }
            // Add timestamp value if needed
            if (!hasTimestamp && (table.equals("direct_messages") || table.equals("channel_messages"))) {
                sqlBuilder.append(", ?");
            }
            sqlBuilder.append(")");
            
            try (PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {
                int paramIndex = 1;
                for (int i = 0; i < values.length; i++) {
                    pstmt.setString(paramIndex++, values[i]);
                }
                // Set timestamp using Korea Standard Time (KST, UTC+9)
                if (!hasTimestamp && (table.equals("direct_messages") || table.equals("channel_messages"))) {
                    String currentTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    pstmt.setString(paramIndex, currentTime);
                }
                pstmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                out.println("ERROR: Failed to save message to database.");
                e.printStackTrace();
                return false;
            }
        }

        private void sendChannelMessage(String sender, String channel, String message, PrintWriter out) {
            if (!saveMessageToDatabase("channel_messages", 
                    new String[]{"sender", "channel", "message"}, 
                    new String[]{sender, channel, message}, out)) {
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
            if (!saveMessageToDatabase("direct_messages", 
                    new String[]{"sender", "receiver", "message"}, 
                    new String[]{sender, receiver, message}, out)) {
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
                out.println("MESSAGE SENT (User " + receiver + " is offline.)");
            }
        }


        private void createTasks(String[] parts, PrintWriter out, String senderIP, String senderHost) {
            if (!checkLoggedIn(out)) return;
            
            if (parts.length < 3) {
                out.println("ERROR: usage: createTask description <task_description> [status <status>] [deadline <deadline>] [assignee <username>]");
                out.println("Example: createTask description Fix bug");
                out.println("Example: createTask description Fix bug status in_progress");
                out.println("Example: createTask description Fix bug deadline 2026-01-08");
                out.println("Example: createTask description Fix bug status in_progress deadline 2026-01-08 assignee Alice");
                out.println("Status options: pending, in_progress, completed");
                return;
            }

            String status = "pending"; // Default status
            String deadline = null;
            String assignee = null;
            String taskDescription = null;
            
            // Parse: createTask description <description> [status <status>] [deadline <deadline>] [assignee <username>]
            // Similar to updateTask: parse field-value pairs
            int i = 1;
            while (i < parts.length) {
                String field = parts[i].toLowerCase();
                
                if (!field.equals("description") && !field.equals("status") && 
                    !field.equals("deadline") && !field.equals("assignee")) {
                    out.println("ERROR: Invalid field: " + field + ". Must be: description, status, deadline, or assignee");
                    return;
                }
                
                i++; // Move to value
                if (i >= parts.length) {
                    out.println("ERROR: Missing value for field: " + field);
                    return;
                }
                
                // Collect value (may span multiple tokens for description)
                StringBuilder valueBuilder = new StringBuilder();
                if (field.equals("description")) {
                    // Description can have multiple words, collect until next field or end
                    while (i < parts.length) {
                        String nextToken = parts[i].toLowerCase();
                        if (nextToken.equals("description") || nextToken.equals("status") || 
                            nextToken.equals("deadline") || nextToken.equals("assignee")) {
                            break; // Next field found
                        }
                        if (valueBuilder.length() > 0) valueBuilder.append(" ");
                        valueBuilder.append(parts[i]);
                        i++;
                    }
                } else {
                    // Single token value for status, deadline, assignee
                    valueBuilder.append(parts[i]);
                    i++;
                }
                
                String value = valueBuilder.toString().trim();
                
                if (field.equals("description")) {
                    taskDescription = value;
                } else if (field.equals("status")) {
                    status = value;
                } else if (field.equals("deadline")) {
                    deadline = value;
                } else if (field.equals("assignee")) {
                    assignee = value;
                }
            }

            if (taskDescription == null || taskDescription.isEmpty()) {
                out.println("ERROR: Task description is required.");
                return;
            }

            if (taskDescription.length() > 2000) {
                out.println("ERROR: Task description too long. Maximum length is 2000 characters.");
                return;
            }
            
            // Validate status
            if (!isValidStatus(status)) {
                out.println("ERROR: Invalid status. Must be: pending, in_progress, or completed");
                return;
            }
            
            // Prevent creating tasks directly as 'completed'
            if (status.equals("completed")) {
                out.println("ERROR: Cannot create a task with 'completed' status. Use 'updateTask' to mark as completed after creation.");
                return;
            }
            
            // Validate assignee if provided
            if (assignee != null && !assignee.isEmpty()) {
                if (!checkUserExists(assignee, out)) return;
            }

            String insertSQL = "INSERT INTO tasks (creator, description, status, deadline, assignee) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSQL, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, currentUsername);
                pstmt.setString(2, taskDescription);
                pstmt.setString(3, status);
                pstmt.setString(4, deadline);
                pstmt.setString(5, assignee);
                pstmt.executeUpdate();
                
                // Get the generated task ID
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int taskId = generatedKeys.getInt(1);
                        out.println("TASK CREATED: Task #" + taskId);
                    } else {
                        out.println("TASK CREATED");
                    }
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to create task.");
                e.printStackTrace();
            }
        }

        private void viewTasks(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;

            // Check if filtering by username
            String filterUser = null;
            if (parts.length >= 2) {
                filterUser = parts[1];
            }

            final String filter = filterUser; // Make final for lambda
            String sql = filterUser != null 
                ? "SELECT id, creator, assignee, description, status, deadline FROM tasks WHERE assignee = ? ORDER BY id"
                : "SELECT id, creator, assignee, description, status, deadline FROM tasks ORDER BY id";
            
            executeQueryWithErrorHandling(sql, "Failed to retrieve tasks.",
                (pstmt) -> {
                    try {
                        if (filter != null) {
                            pstmt.setString(1, filter);
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                (rs, pw) -> {
                    try {
                        boolean hasTasks = false;
                        if (filter != null) {
                            pw.println("=== TASKS (Assigned to: " + filter + ") ===");
                        } else {
                            pw.println("=== TASKS ===");
                        }
                        while (rs.next()) {
                            hasTasks = true;
                            int id = rs.getInt("id");
                            String creator = rs.getString("creator");
                            String assignee = rs.getString("assignee");
                            String description = rs.getString("description");
                            String status = rs.getString("status");
                            String deadline = rs.getString("deadline");
                            
                            pw.print("Task #" + id + ": " + description);
                            pw.print(" [Creator: " + creator + "]");
                            if (assignee != null && !assignee.isEmpty()) {
                                pw.print(" [Assigned to: " + assignee + "]");
                            } else {
                                pw.print(" [Unassigned]");
                            }
                            if (status != null && !status.isEmpty()) {
                                pw.print(" [Status: " + status + "]");
                            }
                            if (deadline != null && !deadline.isEmpty()) {
                                pw.print(" [Deadline: " + deadline + "]");
                            }
                            pw.println();
                        }
                        
                        if (!hasTasks) {
                            pw.println("No tasks found.");
                        }
                        pw.println("============");
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }, out);
        }
        
        private void updateTask(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;
            
            if (parts.length < 3) {
                out.println("ERROR: usage: updateTask <task_id> [field value] [field value] ...");
                out.println("Fields: description, status, deadline, assignee");
                out.println("Example: updateTask 1 description Fix critical bug");
                out.println("Example: updateTask 1 status in_progress");
                out.println("Example: updateTask 1 description Fix bug status in_progress deadline 2024-12-31");
                out.println("Example: updateTask 1 status completed  (only assigned user can mark as completed)");
                return;
            }

            Integer taskId = parseTaskId(parts, 1, out);
            if (taskId == null) return;

            // Get task info (creator and assignee)
            TaskInfo taskInfo = getTaskInfo(taskId, out);
            if (taskInfo == null) return;
            String creator = taskInfo.creator;
            String assignee = taskInfo.assignee;

            // Parse multiple field-value pairs
            // Format: updateTask <task_id> [field1 value1] [field2 value2] ...
            Map<String, String> updates = new HashMap<>();
            int i = 2;
            while (i < parts.length) {
                String field = parts[i].toLowerCase();
                
                if (!field.equals("description") && !field.equals("status") && 
                    !field.equals("deadline") && !field.equals("assignee")) {
                    out.println("ERROR: Invalid field: " + field + ". Must be: description, status, deadline, or assignee");
                    return;
                }
                
                i++; // Move to value
                if (i >= parts.length) {
                    out.println("ERROR: Missing value for field: " + field);
                    return;
                }
                
                // Collect value (may span multiple tokens for description)
                StringBuilder valueBuilder = new StringBuilder();
                if (field.equals("description")) {
                    // Description can have multiple words, collect until next field or end
                    while (i < parts.length) {
                        String nextToken = parts[i].toLowerCase();
                        if (nextToken.equals("description") || nextToken.equals("status") || 
                            nextToken.equals("deadline") || nextToken.equals("assignee")) {
                            break; // Next field found
                        }
                        if (valueBuilder.length() > 0) valueBuilder.append(" ");
                        valueBuilder.append(parts[i]);
                        i++;
                    }
                } else {
                    // Single token value for status, deadline, assignee
                    valueBuilder.append(parts[i]);
                    i++;
                }
                
                String value = valueBuilder.toString().trim();
                updates.put(field, value);
            }

            if (updates.isEmpty()) {
                out.println("ERROR: No fields to update.");
                return;
            }

            // Validate and build update SQL
            List<String> setClauses = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            List<String> updatedFields = new ArrayList<>();

            for (Map.Entry<String, String> entry : updates.entrySet()) {
                String field = entry.getKey();
                String value = entry.getValue();

                // Validate and check permissions
                if (field.equals("description")) {
                    if (value.isEmpty()) {
                        out.println("ERROR: Description cannot be empty.");
                        return;
                    }
                    if (!creator.equals(currentUsername)) {
                        out.println("ERROR: You can only update description of tasks that you created.");
                        return;
                    }
                    setClauses.add("description = ?");
                    values.add(value);
                    updatedFields.add("description");
                } else if (field.equals("status")) {
                    if (!isValidStatus(value)) {
                        out.println("ERROR: Invalid status. Must be: pending, in_progress, or completed");
                        return;
                    }
                    if (value.equals("completed")) {
                        if (assignee == null || assignee.isEmpty()) {
                            out.println("ERROR: Task is not assigned to anyone. Assign the task first.");
                            return;
                        }
                        if (!assignee.equals(currentUsername)) {
                            out.println("ERROR: Only the assigned user can mark a task as completed.");
                            return;
                        }
                    } else {
                        if (!creator.equals(currentUsername)) {
                            out.println("ERROR: You can only update status of tasks that you created.");
                            return;
                        }
                    }
                    setClauses.add("status = ?");
                    values.add(value);
                    updatedFields.add("status");
                } else if (field.equals("deadline")) {
                    if (!creator.equals(currentUsername)) {
                        out.println("ERROR: You can only update deadline of tasks that you created.");
                        return;
                    }
                    setClauses.add("deadline = ?");
                    values.add(value);
                    updatedFields.add("deadline");
                } else if (field.equals("assignee")) {
                    if (!checkUserExists(value, out)) return;
                    setClauses.add("assignee = ?");
                    values.add(value);
                    updatedFields.add("assignee");
                }
            }

            // Build and execute update SQL
            String updateSql = "UPDATE tasks SET " + String.join(", ", setClauses) + " WHERE id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                int paramIndex = 1;
                for (Object val : values) {
                    pstmt.setObject(paramIndex++, val);
                }
                pstmt.setInt(paramIndex, taskId);
                
                int rowsUpdated = pstmt.executeUpdate();
                if (rowsUpdated > 0) {
                    StringBuilder updateMsg = new StringBuilder("TASK UPDATED: Task #" + taskId);
                    for (String field : updatedFields) {
                        updateMsg.append(" ").append(field).append("=").append(updates.get(field));
                    }
                    out.println(updateMsg.toString());
                } else {
                    out.println("ERROR: Failed to update task.");
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to update task.");
                e.printStackTrace();
            }
        }

        private void deleteTask(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;
            
            if (parts.length < 2) {
                out.println("ERROR: usage: deleteTask <task_id>");
                return;
            }

            Integer taskId = parseTaskId(parts, 1, out);
            if (taskId == null) return;

            // Check if task exists and user is creator
            TaskInfo taskInfo = getTaskInfo(taskId, out);
            if (taskInfo == null) return;
            
            if (!taskInfo.creator.equals(currentUsername)) {
                out.println("ERROR: You can only delete tasks that you created.");
                return;
            }

            // Delete task
            String deleteSql = "DELETE FROM tasks WHERE id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                pstmt.setInt(1, taskId);
                int rowsDeleted = pstmt.executeUpdate();
                if (rowsDeleted > 0) {
                    out.println("TASK DELETED: Task #" + taskId + " has been deleted.");
                } else {
                    out.println("ERROR: Failed to delete task.");
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to delete task.");
                e.printStackTrace();
            }
        }

        // Helper method to execute query with error handling
        private void executeQueryWithErrorHandling(String sql, String errorMessage, 
                java.util.function.Consumer<PreparedStatement> paramSetter,
                java.util.function.BiConsumer<ResultSet, PrintWriter> resultProcessor, 
                PrintWriter out) {
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                paramSetter.accept(pstmt);
                try (ResultSet rs = pstmt.executeQuery()) {
                    resultProcessor.accept(rs, out);
                }
            } catch (SQLException e) {
                out.println("ERROR: " + errorMessage);
                e.printStackTrace();
            }
        }

        private void viewDirectMessages(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;

            String sql = "SELECT sender, receiver, message, timestamp FROM direct_messages WHERE receiver = ? OR sender = ? ORDER BY timestamp";
            executeQueryWithErrorHandling(sql, "Failed to retrieve direct messages.",
                (pstmt) -> {
                    try {
                        pstmt.setString(1, currentUsername);
                        pstmt.setString(2, currentUsername);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                (rs, pw) -> {
                    try {
                        boolean hasMessages = false;
                        pw.println("=== DIRECT MESSAGES ===");
                        while (rs.next()) {
                            hasMessages = true;
                            String sender = rs.getString("sender");
                            String receiver = rs.getString("receiver");
                            String message = rs.getString("message");
                            String timestamp = rs.getString("timestamp");
                            
                            if (sender.equals(currentUsername)) {
                                pw.println("To " + receiver + " (" + timestamp + "): " + message);
                            } else {
                                pw.println("From " + sender + " (" + timestamp + "): " + message);
                            }
                        }
                        if (!hasMessages) {
                            pw.println("No direct messages found.");
                        }
                        pw.println("======================");
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }, out);
        }

        private void viewChannelMessages(String[] parts, PrintWriter out) {
            if (!checkLoggedIn(out)) return;

            String channelName = null;
            if (parts.length >= 2) {
                channelName = parts[1];
                if (channelName.startsWith("#")) {
                    channelName = channelName.substring(1);
                }
            } else {
                out.println("ERROR: usage: viewChannelMessages <channel_name>");
                out.println("Example: viewChannelMessages general");
                out.println("Example: viewChannelMessages #general");
                return;
            }

            final String channel = channelName; // Make final for lambda
            String sql = "SELECT sender, channel, message, timestamp FROM channel_messages WHERE channel = ? ORDER BY timestamp";
            executeQueryWithErrorHandling(sql, "Failed to retrieve channel messages.",
                (pstmt) -> {
                    try {
                        pstmt.setString(1, channel);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                (rs, pw) -> {
                    try {
                        boolean hasMessages = false;
                        pw.println("=== CHANNEL MESSAGES (#" + channel + ") ===");
                        while (rs.next()) {
                            hasMessages = true;
                            String sender = rs.getString("sender");
                            String message = rs.getString("message");
                            String timestamp = rs.getString("timestamp");
                            
                            pw.println("[" + timestamp + "] " + sender + ": " + message);
                        }
                        if (!hasMessages) {
                            pw.println("No messages found in channel #" + channel);
                        }
                        pw.println("==========================");
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }, out);
        }
        
        private boolean checkLoggedIn(PrintWriter out) {
            if (currentUsername == null) {
                out.println("ERROR: You must be logged in.");
                return false;
            }
            return true;
        }


        // Helper method to get task info (creator and assignee)
        private TaskInfo getTaskInfo(int taskId, PrintWriter out) {
            String checkTaskSql = "SELECT creator, assignee FROM tasks WHERE id = ?";
            try (PreparedStatement checkPstmt = conn.prepareStatement(checkTaskSql)) {
                checkPstmt.setInt(1, taskId);
                try (ResultSet rs = checkPstmt.executeQuery()) {
                    if (!rs.next()) {
                        out.println("ERROR: Task with ID " + taskId + " not found.");
                        return null;
                    }
                    return new TaskInfo(rs.getString("creator"), rs.getString("assignee"));
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to check task.");
                e.printStackTrace();
                return null;
            }
        }

        // Helper class to hold task information
        private static class TaskInfo {
            String creator;
            String assignee;

            TaskInfo(String creator, String assignee) {
                this.creator = creator;
                this.assignee = assignee;
            }
        }

        // Helper method to check if user exists
        private boolean checkUserExists(String username, PrintWriter out) {
            String checkUserSql = "SELECT username FROM users WHERE username = ?";
            try (PreparedStatement checkPstmt = conn.prepareStatement(checkUserSql)) {
                checkPstmt.setString(1, username);
                try (ResultSet rs = checkPstmt.executeQuery()) {
                    if (!rs.next()) {
                        out.println("ERROR: User '" + username + "' not found.");
                        return false;
                    }
                    return true;
                }
            } catch (SQLException e) {
                out.println("ERROR: Failed to check user.");
                e.printStackTrace();
                return false;
            }
        }

        // Helper method to parse task ID from command parts
        private Integer parseTaskId(String[] parts, int index, PrintWriter out) {
            if (parts.length <= index) {
                return null;
            }
            try {
                return Integer.parseInt(parts[index]);
            } catch (NumberFormatException e) {
                out.println("ERROR: Task ID must be a number.");
                return null;
            }
        }

        // Helper method to validate status
        private boolean isValidStatus(String status) {
            return status.equals("pending") || status.equals("in_progress") || status.equals("completed");
        }

        private void handleFileSend(String[] parts, BufferedReader in) {
            try {
                if (!isLoggedIn) {
                    this.out.println("ERROR: Login required");
                    return;
                }

                if (parts.length < 5) {
                    this.out.println("ERROR: sendFile <channel|user> <destination> <filesize> <filename>");
                    return;
                }

                String type = parts[1]; // channel | user
                String destination = parts[2];
                long fileSize = Long.parseLong(parts[3]);
                
                // Filename is last and may contain spaces, so reconstruct it
                StringBuilder filenameBuilder = new StringBuilder();
                for (int i = 4; i < parts.length; i++) {
                    if (i > 4) filenameBuilder.append(" ");
                    filenameBuilder.append(parts[i]);
                }
                String filename = filenameBuilder.toString();

                this.out.println("READY_FOR_FILE");

                // Read file data as Base64 string from next line
                String base64Data = in.readLine();
                if (base64Data == null) {
                    this.out.println("ERROR: No file data received");
                    return;
                }

                byte[] fileBytes = java.util.Base64.getDecoder().decode(base64Data);
                
                // Verify file size matches
                if (fileBytes.length != fileSize) {
                    this.out.println("ERROR: File size mismatch. Expected: " + fileSize + ", Received: " + fileBytes.length);
                    return;
                }
            
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

                this.out.println("FILE SENT");

            } catch (Exception e) {
                e.printStackTrace();
                this.out.println("ERROR: File transfer failed: " + e.getMessage());
            } 
    }

    // Helper method to create file message format: incomingFile <base64_filename> <size> <base64_data>
    private String createFileMessage(String filename, byte[] data) {
        String base64Data = java.util.Base64.getEncoder().encodeToString(data);
        // Encode filename to Base64 so spaces/special chars don't break parsing
        String encodedFilename = java.util.Base64.getEncoder()
                .encodeToString(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "incomingFile " + encodedFilename + " " + data.length + " " + base64Data;
    }

    private void forwardFile(String type, String destination, String filename, byte[] data) throws IOException {
        synchronized (clients) {
            String fileMessage = createFileMessage(filename, data);

            if (type.equalsIgnoreCase("user")) {
                ClientConnection cc = clients.get(destination);
                if (cc != null) {
                    cc.writer.println(fileMessage);
                }
            }

            else if (type.equalsIgnoreCase("channel")) {
                for (ClientConnection cc : clients.values()) {
                    cc.writer.println(fileMessage);
                }
            }
        }
    }

}
