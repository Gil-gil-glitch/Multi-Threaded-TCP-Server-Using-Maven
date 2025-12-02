package edu;

import java.io.*;
import java.net.*;
import java.sql.*;

public class ClientHandler implements Runnable{

        private Socket client;
        private Connection conn;

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

        private void handleSend(String[] parts, PrintWriter out, String senderIP, String senderHost){
            String dest = parts[1];

            StringBuilder messageBuilder = new StringBuilder();

            for (int i = 2; i < parts.length-2; i++){

                messageBuilder.append(parts[i]);
                if (i < parts.length - 2){
                    messageBuilder.append(" ");
                }

                String message = messageBuilder.toString();

            }

        }
    }