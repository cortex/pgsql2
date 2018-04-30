package org.postgresql.sql2.communication.packets;

import java.nio.charset.StandardCharsets;

public class CommandComplete {
  public enum Types {
    INSERT,
    DELETE,
    CREATE_TABLE,
    CREATE_TYPE,
    UPDATE,
    SELECT,
    MOVE,
    FETCH,
    COPY
  }
  private int numberOfRowsAffected;
  private Types type;

  public CommandComplete(byte[] payload) {
    String message = new String(payload, StandardCharsets.UTF_8);

    if(message.startsWith("INSERT")) {
      type = Types.INSERT;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    } else if(message.startsWith("DELETE")) {
      type = Types.DELETE;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    } else if(message.startsWith("CREATE TABLE")) {
      type = Types.CREATE_TABLE;
      numberOfRowsAffected = 0;
    } else if(message.startsWith("CREATE TYPE")) {
      type = Types.CREATE_TYPE;
      numberOfRowsAffected = 0;
    } else if(message.startsWith("UPDATE")) {
      type = Types.UPDATE;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    } else if(message.startsWith("SELECT")) {
      type = Types.SELECT;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    } else if(message.startsWith("MOVE")) {
      type = Types.MOVE;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    } else if(message.startsWith("FETCH")) {
      type = Types.FETCH;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    } else if(message.startsWith("COPY")) {
      type = Types.COPY;
      numberOfRowsAffected = Integer.parseInt(message.substring(message.lastIndexOf(" ") + 1, message.length() - 1));
    }
  }

  public int getNumberOfRowsAffected() {
    return numberOfRowsAffected;
  }

  public Types getType() {
    return type;
  }
}
