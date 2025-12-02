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
        private static final Map<PrintWriter, String> clientUsernames = new HashMap<>(); // Track username for each client
        private Socket client;
        private Connection conn;
        private PrintWriter out;
        private String currentUsername = null; // Current client's username 


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
                        case "sendMessage"-> handleSend(parts, out, senderIP, senderHost);
                        default -> out.println("ERROR: Unknown command");
                    }
                }

                
            } catch (Exception e){
                e.printStackTrace();
            } finally {
                if (this.out != null) {
                    synchronized (clientWriters) {
                        clientWriters.remove(this.out);
                        synchronized (clientUsernames) {
                            clientUsernames.remove(this.out);
                        }
                        System.out.println("Client disconnected. Remaining clients: " + clientWriters.size());
                    }
                }
                try { client.close(); } catch (IOException ignored) {}
        }
        }

        private void handleLogin(String[] parts, PrintWriter out, String senderIP, String senderHost){

            if (parts.length < 3){
                out.println("ERROR: usage: login <username> <password>");
                return;
            }

            String sql = """
                    SELECT 1 FROM users
                    WHERE username = ? AND password = ?
                    AND ip_address = ? AND hostname = ?
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)){
                pstmt.setString(1, parts[1]);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                ResultSet rs = pstmt.executeQuery();
                if (rs.next()){
                    String username = parts[1];
                    synchronized (clientUsernames) {
                        clientUsernames.put(out, username);
                    }
                    currentUsername = username;
                    out.println("LOGIN OK");
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

            String username = parts[1];
            
            // First check for username duplicate
            String checkSql = """
                    SELECT 1 FROM users
                    WHERE username = ?
                    """;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)){
                checkStmt.setString(1, username);
                ResultSet rs = checkStmt.executeQuery();
                
                if (rs.next()){
                    out.println("REGISTER FAILED: Username '" + username + "' already exists. Please use a different username.");
                    return;
                }
            } catch (SQLException e){
                out.println("REGISTER ERROR: Database query failed");
                e.printStackTrace();
                return;
            }

            // Proceed with registration if username is not duplicate
            String insertSql = """
                    INSERT INTO users (username, password, ip_address, hostname)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)){
                pstmt.setString(1, username);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                pstmt.executeUpdate();
                // Store username after registration (considered as auto-login)
                synchronized (clientUsernames) {
                    clientUsernames.put(out, username);
                }
                currentUsername = username;
                out.println("REGISTER OK");
            } catch (SQLException e){
                // Handle other errors like composite PRIMARY KEY violation
                if (e.getMessage().contains("UNIQUE constraint") || e.getMessage().contains("PRIMARY KEY")) {
                    out.println("REGISTER FAILED: Username '" + username + "' already exists. Please use a different username.");
                } else {
                    out.println("REGISTER FAILED: Database error occurred");
                    e.printStackTrace();
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
    }