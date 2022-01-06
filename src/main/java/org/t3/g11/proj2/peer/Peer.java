package org.t3.g11.proj2.peer;

import org.t3.g11.proj2.keyserver.KeyServer;
import org.t3.g11.proj2.keyserver.KeyServerCMD;
import org.t3.g11.proj2.keyserver.KeyServerReply;
import org.t3.g11.proj2.keyserver.message.UnidentifiedMessage;
import org.t3.g11.proj2.nuttela.GnuNode;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

public class Peer {
    private final ZContext zctx;
    private final ZMQ.Socket ksSocket;

    private PeerData peerData;

    private boolean authenticated;
    private CopyOnWriteArraySet<String> subs;
    private final KeyHolder keyHolder;
    private final GnuNode node;
    private final Thread nodeT;

    public Peer(ZContext zctx, int id, String address, int port) throws Exception {
        this.zctx = zctx;
        this.subs = new CopyOnWriteArraySet<>();
        this.ksSocket = zctx.createSocket(SocketType.REQ);
        if (!this.ksSocket.connect(KeyServer.ENDPOINT)) {
            System.err.println("Failed to connect to keyserver.");
            throw new Exception("Failed to connect to keyserver.");
        }

        this.authenticated = false;
        this.keyHolder = new KeyHolder(KeyServer.KEYINSTANCE, KeyServer.KEYSIZE);

        // TODO unfortunate double reference
        this.node = new GnuNode(this, id, address, port);
        this.nodeT = new Thread(this.node);
    }

    public void startNode() {
        this.nodeT.start();
    }

    public boolean register(String username) {
        // keys stuff
        try {
            this.keyHolder.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Failed to initialize key generator.");
            return false;
        }

        PublicKey publicKey = this.keyHolder.getPublicKey();
        PrivateKey privateKey = this.keyHolder.getPrivateKey();

        ZMsg zMsg = new UnidentifiedMessage(
                KeyServerCMD.REGISTER.toString(),
                Arrays.asList(username, KeyHolder.encodeKey(publicKey))
        ).newZMsg();
        zMsg.send(this.ksSocket);

        ZMsg replyZMsg = ZMsg.recvMsg(this.ksSocket);
        UnidentifiedMessage replyMsg = new UnidentifiedMessage(replyZMsg);
        if (replyMsg.getCmd().equals(KeyServerReply.SUCCESS.toString())) {
            // success
            try {
                KeyHolder.writeKeyToFile(privateKey, username);
            } catch (IOException e) {
                System.err.printf("Failed to save user's private key to a file. Here it is:\n%s\n",
                        KeyHolder.encodeKey(privateKey));
            }

            try {
                KeyHolder.writeKeyToFile(publicKey, username);
            } catch (IOException e) {
                System.err.printf("Failed to save user's public key to a file. Here it is:\n%s\n",
                        KeyHolder.encodeKey(publicKey));
            }

            try {
                this.peerData = new PeerData(username);
                this.peerData.reInitDB();
                this.peerData.addUserSelf(KeyHolder.encodeKey(publicKey));
            } catch (SQLException throwables) {
                throwables.printStackTrace();
                System.err.println("Failed to create database.");
                System.exit(1);
            }
            this.startNode();
            this.authenticated = true;
        } else {
            // failure
            this.authenticated = false;
            this.keyHolder.clear();
        }

        return this.authenticated;
    }

    public boolean authenticate(String username) {
        this.authenticated = false;

        try {
            this.keyHolder.importKeysFromFile(username);
        } catch (IOException | InvalidKeySpecException e) {
            e.printStackTrace();
            return false;
        }

        try {
            this.peerData = new PeerData(username);
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            System.err.println("Failed to open user database.");
            this.keyHolder.clear();
            return false;
        }

        this.startNode();
        this.authenticated = true;
        return true;
    }

    public PublicKey lookup(String username) {
        ZMsg zMsg = new UnidentifiedMessage(
                KeyServerCMD.LOOKUP.toString(),
                Collections.singletonList(username)
        ).newZMsg();
        zMsg.send(this.ksSocket);

        ZMsg replyZMsg = ZMsg.recvMsg(this.ksSocket);
        UnidentifiedMessage replyMsg = new UnidentifiedMessage(replyZMsg);
        if (replyMsg.getCmd().equals(KeyServerReply.SUCCESS.toString())) {
            // success
            try {
                return this.keyHolder.genPubKey(replyMsg.getArg(0));
            } catch (InvalidKeySpecException e) {
                e.printStackTrace();
                return null;
            }
        } else {
            // failure
            return null;
        }
    }

    public boolean newPost(String content) {
        byte[] cipherBuffer = content.getBytes();
        String ciphered;
        try {
            ciphered = this.keyHolder.encryptStr(cipherBuffer);
        } catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException e) {
            System.err.println("Failed to encrypt post content.");
            return false;
        }

        try {
            this.peerData.addPostSelf(content, ciphered);
        } catch (SQLException throwables) {
            System.err.println(throwables.getMessage());
            return false;
        }
        return true;
    }

    public List<HashMap<String, String>> getSelfPeerPosts() {
        try {
            return peerData.getPostsSelf();
        } catch (SQLException throwables) {
            System.err.println(throwables.getMessage());
            return null;
        }
    }


    private PublicKey getUserKey(String username) {
        // fetch from cache
        String pubKeyStr;
        try {
            pubKeyStr = this.peerData.getUserKey(username);
        } catch (SQLException throwables) {
            pubKeyStr = null;
        }

        if (pubKeyStr != null) {
            // key cached
            try {
                return this.keyHolder.genPubKey(pubKeyStr);
            } catch (InvalidKeySpecException e) {
                e.printStackTrace();
                return null;
            }
        }

        // key not cached
        return this.lookup(username);
    }

    public String decypherText(String ciphered, String username) throws Exception {
        PublicKey publicKey = this.getUserKey(username);
        if (publicKey == null) throw new Exception("User " + username + " not found.");

        return new String(
                this.keyHolder.decrypt(
                        Base64.getDecoder().decode(ciphered.getBytes()),
                        publicKey
                )
        );
    }

    public boolean subscribe(String username) {
        return this.subs.add(username);
    }

    public Set<String> getSubs() {
        return this.subs;
    }

    public List<HashMap<String, String>> getUserPosts(String username) {
        try {
            return this.peerData.getPosts(username);
        } catch (SQLException throwables) {
            System.err.println(throwables.getMessage());
            return null;
        }
    }

    public PeerData getPeerData() {
        return peerData;
    }

    public void shutdown() {
    }
}
