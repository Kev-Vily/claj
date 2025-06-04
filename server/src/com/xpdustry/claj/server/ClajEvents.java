package com.xpdustry.claj.server;

import arc.Events;
import arc.net.Connection;
import arc.net.DcReason;

public class ClajEvents {
  /** 
   * Fire an event to the {@link ClajVars#loop}. <br>
   * Do nothing if {@link ClajVars#loop} is {@code null} or not started.
   */
  public static <T> void fire(T type) {
    if (ClajVars.loop == null || !ClajVars.loop.running()) return;
    ClajVars.loop.post(() -> Events.fire(type));
  }
  
  /** 
   * Fire an event to the {@link ClajVars#loop}. <br>
   * Do nothing if {@link ClajVars#loop} is {@code null} or not started.
   */
  public static <T> void fire(Class<?> ctype, T type) {
    if (ClajVars.loop == null || !ClajVars.loop.running()) return;
    ClajVars.loop.post(() -> Events.fire(ctype, type));
  }
  
  
  public static class ServerLoadEvent {}
  public static class ServerStoppingEvent {}
  
  public static class ClientConnectedEvent {
    public final Connection connection;
    
    public ClientConnectedEvent(Connection connection) {
      this.connection = connection;
    }
  }
  /** @apiNote this event comes after {@link RoomClosedEvent} if the connection was the room host. */
  public static class ClientDisonnectedEvent {
    public final Connection connection;
    public final DcReason reason;
    /** not {@code null} if the client was in a room */
    public final ClajRoom room;
    
    public ClientDisonnectedEvent(Connection connection, DcReason reason, ClajRoom room) {
      this.connection = connection;
      this.reason = reason;
      this.room = room;
    }
  }
  
  /** Currently the only reason is for packet spam. */
  public static class ClientKickedEvent {
    public final Connection connection;
    
    public ClientKickedEvent(Connection connection) {
      this.connection = connection;
    }
  }
  /** When a connection join a room */
  public static class ConnectionJoinedEvent {
    public final Connection connection;
    public final ClajRoom room;
    
    public ConnectionJoinedEvent(Connection connection, ClajRoom room) {
      this.connection = connection;
      this.room = room;
    }
  }
  
  public static class RoomCreatedEvent {
    public final ClajRoom room;
    
    public RoomCreatedEvent(ClajRoom room) {
      this.room = room;
    }
  }
  public static class RoomClosedEvent {
    /** @apiNote the room is closed, so it cannot be used anymore. */
    public final ClajRoom room;
    
    public RoomClosedEvent(ClajRoom room) {
      this.room = room;
    }
  }
  public static class RoomCreationRejectedEvent {
    /** the connection that tried to create the room */
    public final Connection connection;
    public final ClajPackets.RoomClosedPacket.CloseReason reason;
    
    public RoomCreationRejectedEvent(Connection connection, ClajPackets.RoomClosedPacket.CloseReason reason) {
      this.connection = connection;
      this.reason = reason;
    }
  }
  
  /** 
   * Defines an action tried by a connection but was not allowed to do it.
   * <p>
   * E.g. a client of the room tried to close it, or the host tried to join another room while hosting one.
   */
  public static class ActionDeniedEvent {
    public final Connection connection;
    public final ClajPackets.ClajMessage2Packet.MessageType reason;
    
    public ActionDeniedEvent(Connection connection, ClajPackets.ClajMessage2Packet.MessageType reason) {
      this.connection = connection;
      this.reason = reason;
    }
  }
  
  public static class PacketTransmittedEvent {
    public final Connection connection;
    public final ClajRoom room;
    
    public PacketTransmittedEvent(Connection connection, ClajRoom room) {
      this.connection = connection;
      this.room = room;
    }
  }
}
