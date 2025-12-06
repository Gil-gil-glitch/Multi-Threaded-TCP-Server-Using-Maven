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

                    switch (parts[0]){
                        case "login" -> handleLogin(parts, out, senderIP, senderHost);
                        case "register"-> handleRegister(parts, out, senderIP, senderHost);
                        case "sendChannelMessage"-> handleChannelSend(parts, out, senderIP, senderHost);
                        case "sendDirectMessage"-> handleSend(parts, out, senderIP, senderHost);
                        case "createTask"-> createTasks(parts, out, senderIP, senderHost);
                        case "assignTask" -> assignTasks(parts, out);
                        case "viewTasks" -> viewTasks(parts, out);
                        default -> out.println("ERROR: Unknown command");
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

            try (Connection conn = DriverManager.getConnection(USERS_DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, parts[1]);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                ResultSet rs = pstmt.executeQuery();
                if (rs.next()){
                    String username = parts[1];
                    synchronized (clientUsernames){
                        clientUsernames.put(this.out, username);
                    }
                    currentUsername = username;
                    out.println("LOGIN OK");
                    isLoggedIn = true;

                    String updateLoginSQL = """
                            UPDATE users SET loggedin = 1
                            WHERE username = ?;
                            """;
                    try (PreparedStatement updatePstmt = conn.prepareStatement(updateLoginSQL)){
                        updatePstmt.setString(1, username);
                        updatePstmt.executeUpdate();
                    } catch (SQLException e){
                        e.printStackTrace();
                    }

                } else {
                    out.println("LOGIN FAILED");        
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
                out.println("REGISTER FAIED");
                e.printStackTrace();
            }
        }

        private void handleChannelSend(String[] parts, PrintWriter out, String senderIP, String senderHost){
            //TO DO: Complete channel messaging implementation. Direct message handling will be added later by Stella.

            String sender = currentUsername;
            if (sender == null) {
                out.println("ERROR: You must be logged in to send messages.");
                return;
            }

            String channel = parts[1];

            StringBuilder messageBuilder = new StringBuilder();

            for (int i = 2; i < parts.length; i++){

                messageBuilder.append(parts[i]);
                if (i < parts.length - 1){
                    messageBuilder.append(" ");
                }

            }

            String message = messageBuilder.toString().trim();

            if (message.isEmpty()) {
                 out.println("ERROR: Message content cannot be empty.");
            return;
            
            }

             String insertSql = """
                    INSERT INTO channel_messages (sender, channel, message)
                    VALUES (?, ?, ?)
                    """;
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

            synchronized (clientWriters) {
                synchronized (clientUsernames) {
                    for (Map.Entry<PrintWriter, String> entry : clientUsernames.entrySet()) {
                        if (entry.getValue().equals(channel)) {
                            PrintWriter receiverWriter = entry.getKey();
                            receiverWriter.println(String.format("receivedMessage %s \"%s\"", sender, message));
                            break;
                        }
                    }
                }
            }

            String broadcastMessage = String.format("MSG #%s: %s", channel, message);

            synchronized(clientWriters){

            for (PrintWriter writer : clientWriters){
                writer.println(broadcastMessage);
                }
            }

        }

        private void handleSend(String[] parts, PrintWriter out, String senderIP, String senderHost){
            if (parts.length < 3) {
                out.println("ERROR: usage: sendMessage <receiver> \"<message>\"");
                return;
            }

            // Check currently logged-in user
            String sender = currentUsername;
            if (sender == null) {
                out.println("ERROR: You must be logged in to send messages.");
                return;
            }

            String receiver = parts[1];
            
            // Parse message wrapped in quotes
            StringBuilder messageBuilder = new StringBuilder();
            boolean inQuotes = false;
            
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i];
                if (part.startsWith("\"") && !inQuotes) {
                    inQuotes = true;
                    messageBuilder.append(part.substring(1));
                    if (part.endsWith("\"") && part.length() > 1) {
                        inQuotes = false;
                        messageBuilder.setLength(messageBuilder.length() - 1);
                        break;
                    }
                } else if (inQuotes) {
                    if (part.endsWith("\"")) {
                        inQuotes = false;
                        messageBuilder.append(" ").append(part.substring(0, part.length() - 1));
                        break;
                    } else {
                        messageBuilder.append(" ").append(part);
                    }
                } else {
                    messageBuilder.append(part);
                    if (i < parts.length - 1) {
                        messageBuilder.append(" ");
                    }
                }
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

            // Save message to database
            String insertSql = """
                    INSERT INTO direct_messages (sender, receiver, message)
                    VALUES (?, ?, ?)
                    """;
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

            // Check if receiver is currently connected and send message
            boolean receiverFound = false;
            synchronized (clientWriters) {
                synchronized (clientUsernames) {
                    for (Map.Entry<PrintWriter, String> entry : clientUsernames.entrySet()) {
                        if (entry.getValue().equals(receiver)) {
                            PrintWriter receiverWriter = entry.getKey();
                            receiverWriter.println(String.format("receivedMessage %s \"%s\"", sender, message));
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
            if (parts.length < 2) {
                out.println("ERROR: usage: createTask <task_description>");
                return;
            }

            String creator = null;

            String findUserSql = """
                    SELECT username
                    FROM users
                    WHERE ip_address = ? AND hostname = ? AND loggedin = 1
                    LIMIT 1
                    """;

            try (Connection conn = DriverManager.getConnection(USERS_DB_URL);
                PreparedStatement findPstmt = conn.prepareStatement(findUserSql)) {

                findPstmt.setString(1, senderIP);
                findPstmt.setString(2, senderHost);

                try (ResultSet rs = findPstmt.executeQuery()) {
                    if (rs.next()) {
                        creator = rs.getString("username");
                    }
                }
            } catch (SQLException e) {
                out.println("ERROR: Database error while checking logged-in user.");
                e.printStackTrace();
                return;
            }

            if (creator == null) {
                out.println("ERROR: No logged-in user found for this machine. Please login first.");
                return;
            }

            StringBuilder taskDescriptionBuilder = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) taskDescriptionBuilder.append(" ");
                taskDescriptionBuilder.append(parts[i]);
            }

            String taskDescription = taskDescriptionBuilder.toString().trim();

            if (taskDescription.isEmpty()) {
                out.println("ERROR: taskDescription content cannot be empty.");
                return;
            }

            if (taskDescription.length() > 2000) {
                out.println("ERROR: taskDescription too long. Maximum length is 2000 characters.");
                return;
            }

            String insertSQL = """
                    INSERT INTO tasks (creator, description)
                    VALUES (?, ?)
                    """;

            try (Connection conn = DriverManager.getConnection(USERS_DB_URL);
                PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {

                pstmt.setString(1, creator);
                pstmt.setString(2, taskDescription);
                pstmt.executeUpdate();
                out.println("TASK CREATED");
            } catch (SQLException e) {
                out.println("ERROR: Failed to create task.");
                e.printStackTrace();
            }
        }


        private void assignTasks(String[] parts, PrintWriter out) {
            // To be implemented
        }

        private void viewTasks(String[] parts, PrintWriter out) {
            // To be implemented
        }
    }