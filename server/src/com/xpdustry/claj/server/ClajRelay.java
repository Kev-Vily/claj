package com.xpdustry.claj.server;

import java.nio.ByteBuffer;

import com.xpdustry.claj.server.util.NetworkSpeed;
import com.xpdustry.claj.server.util.Strings;

import arc.net.Connection;
import arc.net.DcReason;
import arc.net.FrameworkMessage;
import arc.net.FrameworkMessage.*;
import arc.net.NetListener;
import arc.net.NetSerializer;
import arc.net.Server;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.LongMap;
import arc.util.Log;
import arc.util.Ratekeeper;
import arc.util.Threads;
import arc.util.io.ByteBufferInput;
import arc.util.io.ByteBufferOutput;


public class ClajRelay extends Server implements NetListener {
  protected boolean closed;
  /** 
   * Keeps a cache of packets received from connections that are not yet in a room. (queue of 3 packets)<br>
   * Because sometimes {@link ClajPackets.RoomJoinPacket} comes after {@link Packets.ConnectPacket}, 
   * when the client connection is slow, so the server will ignore this essential packet and the client
   * will waits until the timeout.
   */
  protected final IntMap<ByteBuffer[]> packetQueue = new IntMap<>();
  /** Size of the packet queue */
  protected final int packetQueueSize = 3;
  /** Keeps a cache of already notified idling connection, to avoid packet spamming. */
  protected final IntSet notifiedIdle = new IntSet();
  /** List of created rooms */
  public final LongMap<ClajRoom> rooms = new LongMap<>();
  
  public ClajRelay() { this(null); }
  public ClajRelay(NetworkSpeed speedCalculator) {
    super(32768, 16384, new Serializer(speedCalculator));
    addListener(this);
  }

  @Override
  public void run() {
    closed = false;
    super.run();
  }
  
  @Override
  public void stop() {
    closed = true;
    
    // Notify stopping
    ClajEvents.fire(new ClajEvents.ServerStoppingEvent());
    
    if (ClajConfig.warnClosing && !rooms.isEmpty()) {
      Log.info("Notifying rooms that the server is closing...");
      
      try {
        // Notify all rooms that the server will be closed
        rooms.values().forEach(r -> 
          r.message(ClajPackets.ClajMessage2Packet.MessageType.serverClosing));  
      
        // Yea we needs a new thread... because we don't have arc.Timer
        Threads.thread(() -> {
          // Give time to message to be send to all clients
          try { Thread.sleep(2000); }
          catch (InterruptedException ignored) {}
          closeRooms();
          super.stop();
        });
        return;
      } catch (Throwable ignored) {}
    }

    closeRooms();
    super.stop();
  }
  
  public boolean isClosed() {
    return closed;
  }
  
  public void closeRooms() {
    try { rooms.values().forEach(r -> r.close(ClajPackets.RoomClosedPacket.CloseReason.serverClosed)); } 
    catch (Throwable ignored) {}
    rooms.clear();  
  }
  
  @Override
  public void connected(Connection connection) {
    if (isClosed() || ClajConfig.blacklist.contains(Strings.getIP(connection))) {
      connection.close(DcReason.closed);
      return;
    }
  
    Log.debug("Connection @ received.", Strings.conIDToString(connection));
    connection.setArbitraryData(new Ratekeeper());
    ClajEvents.fire(new ClajEvents.ClientConnectedEvent(connection));
  }
  
  @Override
  public void disconnected(Connection connection, DcReason reason) {
    Log.debug("Connection @ lost: @.", Strings.conIDToString(connection), reason);
    notifiedIdle.remove(connection.getID());
    packetQueue.remove(connection.getID());
    
    // Avoid searching for a room if it was an invalid connection or just a ping
    if (!(connection.getArbitraryData() instanceof Ratekeeper)) return;
    
    ClajRoom room = find(connection);
    
    if (room != null) {
      room.disconnected(connection, reason);
      // Remove the room if it was the host
      if (connection == room.host) {
        rooms.remove(room.id);
        Log.info("Room @ closed because connection @ (the host) has disconnected.", room.idString, 
                 Strings.conIDToString(connection));
        ClajEvents.fire(new ClajEvents.RoomClosedEvent(room));
      } else Log.info("Connection @ left the room @.",  Strings.conIDToString(connection), room.idString);  
    }
    
    ClajEvents.fire(new ClajEvents.ClientDisonnectedEvent(connection, reason, room));
  }
  
  @Override
  public void received(Connection connection, Object object) {
    if (!(connection.getArbitraryData() instanceof Ratekeeper) || (object instanceof FrameworkMessage)) return;
    notifiedIdle.remove(connection.getID());
    Ratekeeper rate = (Ratekeeper)connection.getArbitraryData();
    ClajRoom room = find(connection);
    
    // Simple packet spam protection, ignored for room hosts
    if ((room == null || room.host != connection) && 
        ClajConfig.spamLimit > 0 && !rate.allow(3000L, ClajConfig.spamLimit)) {

      if (room != null) {
        room.message(ClajPackets.ClajMessage2Packet.MessageType.packetSpamming);
        room.disconnected(connection, DcReason.closed);
      }
      
      connection.close(DcReason.closed);   
      Log.warn("Connection @ disconnected for packet spamming.", Strings.conIDToString(connection));
      ClajEvents.fire(new ClajEvents.ClientKickedEvent(connection));
      
    // Compatibility for the xzxADIxzx's version
    } else if ((object instanceof String) && ClajConfig.warnDeprecated) {
      connection.sendTCP("[scarlet][[CLaJ Server]:[] Your CLaJ version is obsolete! Please update it by "
                       + "installing the 'claj' mod, in the mod browser.");
      connection.close(DcReason.error);
      Log.warn("Rejected room creation of connection @ for incompatible version.", Strings.conIDToString(connection));
      ClajEvents.fire(new ClajEvents.RoomCreationRejectedEvent(connection, ClajPackets.RoomClosedPacket.CloseReason.obsoleteClient));
      
    } else if (object instanceof ClajPackets.RoomJoinPacket) {
      // Disconnect from a potential another room.
      if (room != null) {
        // Ignore if it's the host of another room
        if (room.host == connection) {
          room.message(ClajPackets.ClajMessage2Packet.MessageType.alreadyHosting);
          Log.warn("Connection @ tried to join the room @ but is already hosting the room @.", 
                   Strings.conIDToString(connection), 
                   Strings.longToBase64(((ClajPackets.RoomJoinPacket)object).roomId), room.idString);
          ClajEvents.fire(new ClajEvents.ActionDeniedEvent(connection, ClajPackets.ClajMessage2Packet.MessageType.alreadyHosting));
          return;
        }
        room.disconnected(connection, DcReason.closed);
      }

      room = get(((ClajPackets.RoomJoinPacket)object).roomId);
      if (room != null) {
        room.connected(connection);
        Log.info("Connection @ joined the room @.", Strings.conIDToString(connection), room.idString);
        
        // Send the queued packets of connections to room host
        ByteBuffer[] queue = packetQueue.remove(connection.getID());
        if (queue != null) {
          Log.debug("Sending queued packets of connection @ to room host.", Strings.conIDToString(connection));
          for (int i=0; i<queue.length; i++) {
            if (queue[i] != null) room.received(connection, queue[i]);
          }
        }
        
        ClajEvents.fire(new ClajEvents.ConnectionJoinedEvent(connection, room));
        
      //TODO: make a limit to avoid room searching; e.g. if more than 100 in one minute, ignore request for 10 min
      } else connection.close(DcReason.error);

    } else if (object instanceof ClajPackets.RoomCreationRequestPacket) {
      // Ignore room creation requests when the server is closing
      if (isClosed()) {
        ClajPackets.RoomClosedPacket p = new ClajPackets.RoomClosedPacket();
        p.reason = ClajPackets.RoomClosedPacket.CloseReason.serverClosed;
        connection.sendTCP(p);
        connection.close(DcReason.error);
        ClajEvents.fire(new ClajEvents.RoomCreationRejectedEvent(connection, p.reason));
        return;
      }
      
      // Check the version of client
      String version = ((ClajPackets.RoomCreationRequestPacket)object).version;
      // Ignore the last part of version, the minor part. (versioning format: 2.major.minor)
      // The minor part is used when no changes have been made to the protocol itself. (sending/receiving way)
      if (version == null || Strings.isVersionAtLeast(version, ClajVars.serverVersion, 2)) {
        ClajPackets.RoomClosedPacket p = new ClajPackets.RoomClosedPacket();
        p.reason = ClajPackets.RoomClosedPacket.CloseReason.outdatedVersion;
        connection.sendTCP(p);
        connection.close(DcReason.error);
        Log.warn("Rejected room creation of connection @ for outdated version.", Strings.conIDToString(connection));
        ClajEvents.fire(new ClajEvents.RoomCreationRejectedEvent(connection, p.reason));
        return;
      }
      
      // Ignore if the connection is already in a room or hold one
      if (room != null) {
        room.message(ClajPackets.ClajMessage2Packet.MessageType.alreadyHosting);
        Log.warn("Connection @ tried to create a room but is already hosting the room @.", 
                 Strings.conIDToString(connection), room.idString);
        ClajEvents.fire(new ClajEvents.ActionDeniedEvent(connection, ClajPackets.ClajMessage2Packet.MessageType.alreadyHosting));
        return;
      }

      room = new ClajRoom(newRoomId(), connection);
      rooms.put(room.id, room);
      room.create();
      Log.info("Room @ created by connection @.", room.idString, Strings.conIDToString(connection));
      ClajEvents.fire(new ClajEvents.RoomCreatedEvent(room));

    } else if (object instanceof ClajPackets.RoomClosureRequestPacket) {
      // Only room host can close the room
      if (room == null) return;
      if (room.host != connection) {
        room.message(ClajPackets.ClajMessage2Packet.MessageType.roomClosureDenied);
        Log.warn("Connection @ tried to close the room @ but is not the host.", Strings.conIDToString(connection),
                 room.idString);
        ClajEvents.fire(new ClajEvents.ActionDeniedEvent(connection, ClajPackets.ClajMessage2Packet.MessageType.roomClosureDenied));
        return;
      }

      rooms.remove(room.id);
      room.close();
      Log.info("Room @ closed by connection @ (the host).", room.idString, Strings.conIDToString(connection));
      ClajEvents.fire(new ClajEvents.RoomClosedEvent(room));
    
    } else if (object instanceof ClajPackets.ConnectionClosedPacket) {
      // Only room host can request a connection closing
      if (room == null) return;
      if (room.host != connection) {
        room.message(ClajPackets.ClajMessage2Packet.MessageType.conClosureDenied);
        Log.warn("Connection @ tried to close the connection @ but is not the host of room @.", 
                 Strings.conIDToString(connection), 
                 Strings.conIDToString(((ClajPackets.ConnectionClosedPacket)object).conID), room.idString);
        ClajEvents.fire(new ClajEvents.ActionDeniedEvent(connection, ClajPackets.ClajMessage2Packet.MessageType.conClosureDenied));
        return;
      }
      
      int conID = ((ClajPackets.ConnectionClosedPacket)object).conID;
      Connection con = arc.util.Structs.find(getConnections(), c -> c.getID() == conID);
      DcReason reason = ((ClajPackets.ConnectionClosedPacket)object).reason;
      
      // Ignore when trying to close itself or closing one that not in the same room
      if (con == connection || !room.contains(con)) {
        Log.warn("Connection @ (room @) tried to close a connection from another room.", 
                 Strings.conIDToString(connection), room.idString);
        return;
      }
      
      if (con != null) {
        Log.info("Connection @ (room @) closed the connection @.", Strings.conIDToString(connection), 
                 Strings.conIDToString(con), room.idString);
        room.disconnectedQuietly(con, reason);
        con.close(reason);
        // An event for this kind of thing is useless, there are #disconnected() for that
      }
      
    // Ignore if the connection is not in a room
    } else if (room != null) {
      if (room.host == connection && (object instanceof ClajPackets.ConnectionWrapperPacket))
        notifiedIdle.remove(((ClajPackets.ConnectionWrapperPacket)object).conID);
      
      room.received(connection, object);
      // Can be used to make metrics about rooms activity
      ClajEvents.fire(new ClajEvents.PacketTransmittedEvent(connection, room));
      
    // Puts in queue; if full, future packets will be ignored.
    } else if (object instanceof ByteBuffer) {
      ByteBuffer[] queue = packetQueue.get(connection.getID(), () -> new ByteBuffer[packetQueueSize]);
      ByteBuffer buffer = (ByteBuffer)object;
      
      for (int i=0; i<queue.length; i++) {
        if (queue[i] == null) {
          queue[i] = (ByteBuffer)ByteBuffer.allocate(buffer.remaining()).put(buffer).rewind();
          break;
        }
      }
    }
  }
  
  /** Does nothing if the connection idle state has already been notified to the room host. */
  @Override
  public void idle(Connection connection) {
    if (!(connection.getArbitraryData() instanceof Ratekeeper)) return;
    if (!notifiedIdle.add(connection.getID())) return;

    ClajRoom room = find(connection);
    if (room != null) room.idle(connection);
  }
  
  public long newRoomId() {
    long id;
    /* re-roll if -1 because it's used to specify an uncreated room */ 
    do { id = arc.math.Mathf.rand.nextLong(); } 
    while (id == -1 || rooms.containsKey(id));
    return id;
  }
  
  public ClajRoom get(long roomId) {
    return rooms.get(roomId);
  }
  
  public ClajRoom find(Connection con) {
    for (ClajRoom r : rooms.values()) {
      if (r.contains(con)) return r;
    }
    return null;  
  }


  public static class Serializer implements NetSerializer {
    private final ThreadLocal<ByteBuffer> last = arc.util.Threads.local(() -> ByteBuffer.allocate(16384));
    private final NetworkSpeed networkSpeed;
    private int lastPos;
    
    public Serializer(NetworkSpeed networkSpeed) {
      this.networkSpeed = networkSpeed;
    }
    
    @Override
    public Object read(ByteBuffer buffer) {
      if (networkSpeed != null) networkSpeed.addDownloadMark(buffer.remaining());
      
      byte id = buffer.get();

      if (id == -2/*framework id*/) return readFramework(buffer);
      if (id == -3/*old claj version*/ && ClajConfig.warnDeprecated) {
        try { return new ByteBufferInput(buffer).readUTF(); }
        catch (Exception e) { throw new RuntimeException(e); }
      }
      if (id == ClajPackets.id) {
        ClajPackets.Packet packet = ClajPackets.newPacket(buffer.get());
        packet.read(new ByteBufferInput(buffer));
        if (packet instanceof ClajPackets.ConnectionPacketWrapPacket) // This one is special
          ((ClajPackets.ConnectionPacketWrapPacket)packet).buffer = 
              (ByteBuffer)((ByteBuffer)last.get().clear()).put(buffer).flip();
        return packet;
      }

      // Non-claj packets are saved as raw buffer, to avoid re-serialization
      return ((ByteBuffer)last.get().clear()).put((ByteBuffer)buffer.position(buffer.position()-1)).flip();
    }
    
    @Override
    public void write(ByteBuffer buffer, Object object) {
      if (networkSpeed != null) lastPos = buffer.position();
      
      if (object instanceof ByteBuffer) {
        buffer.put((ByteBuffer)object);
          
      } else if (object instanceof FrameworkMessage) {
        buffer.put((byte)-2); //framework id
        writeFramework(buffer, (FrameworkMessage)object);
        
      } else if ((object instanceof String) && ClajConfig.warnDeprecated) {
        buffer.put((byte)-3/*old claj version*/);
        try { new ByteBufferOutput(buffer).writeUTF((String)object); }
        catch (Exception e) { throw new RuntimeException(e); }          
        
      } else if (object instanceof ClajPackets.Packet) {
        ClajPackets.Packet packet = (ClajPackets.Packet)object;
        buffer.put(ClajPackets.id).put(ClajPackets.getId(packet));
        packet.write(new ByteBufferOutput(buffer));
        if (packet instanceof ClajPackets.ConnectionPacketWrapPacket) // This one is special
          buffer.put(((ClajPackets.ConnectionPacketWrapPacket)packet).buffer);
      }
      
      if (networkSpeed != null) networkSpeed.addUploadMark(buffer.position() - lastPos);
    }

    public void writeFramework(ByteBuffer buffer, FrameworkMessage message) {
      if (message instanceof Ping) {
        Ping ping = (Ping)message;
        buffer.put((byte) 0).putInt(ping.id).put(ping.isReply ? (byte)1 : 0);
      } else if (message instanceof DiscoverHost) buffer.put((byte)1);
      else if (message instanceof KeepAlive) buffer.put((byte)2);
      else if (message instanceof RegisterUDP) buffer.put((byte)3).putInt(((RegisterUDP)message).connectionID);
      else if (message instanceof RegisterTCP) buffer.put((byte)4).putInt(((RegisterTCP)message).connectionID);
    }

    public FrameworkMessage readFramework(ByteBuffer buffer) {
      byte id = buffer.get();

      if (id == 0) {
          Ping p = new Ping();
          p.id = buffer.getInt();
          p.isReply = buffer.get() == 1;
          return p;
      } else if (id == 1) {
          return FrameworkMessage.discoverHost;
      } else if (id == 2) {
          return FrameworkMessage.keepAlive;
      } else if (id == 3) {
          RegisterUDP p = new RegisterUDP();
          p.connectionID = buffer.getInt();
          return p;
      } else if (id == 4) {
          RegisterTCP p = new RegisterTCP();
          p.connectionID = buffer.getInt();
          return p;
      } else {
        throw new RuntimeException("Unknown framework message!");
      }
    }
  }
}
