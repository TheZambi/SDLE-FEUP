package org.t3.g11.proj2.peer;

import java.sql.*;

public class PeerData {
    private final Connection connection;
    private final String username;

    public PeerData(String username) throws SQLException {
        this.username = username;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + username + ".db");
    }

    public void reInitDB() throws SQLException {
        Statement stmt = this.connection.createStatement();
        stmt.execute("DROP TABLE IF EXISTS User");
        stmt.execute("""
                CREATE TABLE User (
                  user_id INTEGER PRIMARY KEY ASC,
                  user_username TEXT UNIQUE NOT NULL,
                  user_pubkey TEXT NOT NULL
                )
                """);
        stmt.execute("DROP TABLE IF EXISTS Post");
        stmt.execute("""
                CREATE TABLE Post (
                  post_id INTEGER PRIMARY KEY ASC,
                  post_date INTEGER NOT NULL DEFAULT (strftime('%s', CURRENT_TIMESTAMP)),
                  post_ciphered TEXT NOT NULL,
                  post_content TEXT NOT NULL,
                  user_id INTEGER NOT NULL,
                  FOREIGN KEY(user_id) REFERENCES User
                )
                """);
        stmt.close();
    }

    public void addUser(String username, String pubkey) throws SQLException {
        PreparedStatement pstmt = this.connection.prepareStatement("INSERT INTO User(user_username, user_pubkey) VALUES(?, ?)");
        pstmt.setString(1, username);
        pstmt.setString(2, pubkey);
        pstmt.executeUpdate();
        pstmt.close();
    }

    public void addUserSelf(String pubkey) throws SQLException {
        this.addUser(this.username, pubkey);
    }

    public void addPost(int user_id, String content, String ciphered) throws SQLException {
        PreparedStatement pstmt = this.connection.prepareStatement("INSERT INTO Post(post_content, post_ciphered, user_id) VALUES(?, ?, ?)");
        pstmt.setString(1, content);
        pstmt.setString(2, ciphered);
        pstmt.setInt(3, user_id);
        pstmt.executeUpdate();
        pstmt.close();
    }

    public void addPost(String user_username, String content, String ciphered) throws SQLException {
        PreparedStatement pstmt = this.connection.prepareStatement("SELECT user_id FROM User WHERE user_username = ?");
        pstmt.setString(1, user_username);
        ResultSet res = pstmt.executeQuery();
        if (!res.next()) throw new SQLException("User " + user_username + " not found");
        int user_id = res.getInt("user_id");
        pstmt.close();

        this.addPost(user_id, content, ciphered);
    }

    public void addPostSelf(String content, String ciphered) throws SQLException {
        this.addPost(this.username, content, ciphered);
    }
}