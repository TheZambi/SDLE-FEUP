package org.t3.g11.proj2.nuttela.message;

import java.io.Serializable;

public class Result implements Serializable {
    public final int date;
    public final String ciphered;
    public final String author;

    public Result(int date, String ciphered, String author) {
        this.date = date;
        this.ciphered = ciphered;
        this.author = author;
    }
}