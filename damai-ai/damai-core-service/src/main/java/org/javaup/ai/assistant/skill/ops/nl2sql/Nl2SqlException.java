package org.javaup.ai.assistant.skill.ops.nl2sql;

public class Nl2SqlException extends RuntimeException {

    public Nl2SqlException(String message) {
        super(message);
    }

    public Nl2SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
