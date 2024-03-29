package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import play.libs.Akka;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.japi.Creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.plugin.RedisPlugin;

/**
 * A chat room is an Actor.
 */
public class ChatRoom extends UntypedActor {
    
    // Default room.
    //static ActorRef defaultRoom = Akka.system().actorOf(Props.create(ChatRoom.class));
    private static final String CHANNEL = "messages";
    private static final String MEMBERS = "members";
    
    public static Props props(final int roomNumber) {
        return Props.create(new Creator<ChatRoom>() {
          private static final long serialVersionUID = 1L;
     
          @Override
          public ChatRoom create() throws Exception {
            return new ChatRoom(roomNumber);
          }
        });
      }
    
    public static ActorRef getRoom(int id) {
    	ActorRef newRoom = Akka.system().actorOf(ChatRoom.props(id));
    	return newRoom;
    }    
    
    private final String room;
    private final String roomChannel;
    private final String roomMemberList;
    private final ChatRoomListener listener;

    // Users connected to this node
    Map<String, WebSocket.Out<JsonNode>> members = new HashMap<String, WebSocket.Out<JsonNode>>();
  
    public ChatRoom(int roomNumber){
    	super();
    	room = String.valueOf(roomNumber);
    	roomChannel = room + CHANNEL;
    	roomMemberList = room + MEMBERS;
    	listener = new ChatRoomListener(this.getSelf());

    	Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();    	            	
    	j.subscribe(listener, roomChannel);
    	play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);
    	
    	//add the robot
    	new Robot(this.getSelf());
    }
    
    /**
     * Add a new user to this room.
     */
    public static void join(final String username, final int room, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out) throws Exception{
        System.out.println("joining: " + username + " in room " + room);
        
        ActorRef joinRoom = ChatRoom.getRoom(room);
        
        // Join the default room. Timeout should be longer than the Redis connect timeout (2 seconds)
        String result = (String)Await.result(ask(joinRoom,new Join(username, out, in), 3000), Duration.create(3, SECONDS));
        
        if(!"OK".equals(result)) {
            
            // Cannot connect, create a Json error.
            ObjectNode error = Json.newObject();
            error.put("error", result);
            
            // Send the error to the socket.
            out.write(error);
            
        }
        
    }
   
    private void addUser(final String username, Jedis j, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out){
    	
    	//Add the member to this node and the global roster
		members.put(username, out);
		j.sadd(roomMemberList, username);
		
		//Publish the join notification to all nodes
		RosterNotification rosterNotify = new RosterNotification(username, "join");
		j.publish(roomChannel, Json.stringify(Json.toJson(rosterNotify)));
    	
    	// For each event received on the socket,
        in.onMessage(new Callback<JsonNode>() {
           public void invoke(JsonNode event) {
               
        	   Talk talk = new Talk(username, event.get("text").asText());
        	   
        	   Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
        	   try {
        		   //All messages are pushed through the pub/sub channel
        		   j.publish(roomChannel, Json.stringify(Json.toJson(talk)));
        	   } finally {
             	  play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);            		   
        	   }
        	   
           } 
        });
        
        // When the socket is closed.
        in.onClose(new Callback0() {
           public void invoke() {          
               // Send a Quit message to the room.
               removeUser(username);
           }
        });
    }
    
    private void removeUser(final String username)  {
    	Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();  	
    	try{
			//Remove the member from this node and the global roster
			members.remove(username);
			j.srem(roomMemberList, username);
			
			//Publish the quit notification to all nodes
			RosterNotification rosterNotify = new RosterNotification(username, "quit");
			j.publish(roomChannel, Json.stringify(Json.toJson(rosterNotify)));
    	}
    	finally{
    		play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);
    	}
    }
  
    public void onReceive(Object message) throws Exception {
 
 	   Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
    	
        try {        	
        	if(message instanceof Join) {
        		// Received a Join message
        		Join join = (Join)message;
        		// Check if this username is free.
        		if(j.sismember(roomMemberList, join.username)) {
        			getSender().tell("This username is already used", getSelf());
        		} else {
        			addUser(join.username, j, join.in, join.out);
        			getSender().tell("OK", getSelf());
        		}
        	} else if(message instanceof RosterNotification) {
        		//Received a roster notification
        		RosterNotification rosterNotify = (RosterNotification) message;
        		if("join".equals(rosterNotify.direction)) {
        			notifyAll("join", rosterNotify.username, "has entered the room");
        		} else if("quit".equals(rosterNotify.direction)) {
        			notifyAll("quit", rosterNotify.username, "has left the room");
        		}
        	} else if(message instanceof Talk)  {
        		// Received a Talk message
        		Talk talk = (Talk)message;
        		notifyAll("talk", talk.username, talk.text);
        	} else if(message instanceof Ping)  {
        		// Send a ping to all clients to keep sockets open
        		pingAll();	
        	} else {
        		unhandled(message);
        	}
        } finally {
        	play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);            		   
        }  
    }
    
    private void pingAll(){
    	for(WebSocket.Out<JsonNode> channel: members.values()) {
    		ObjectNode event = Json.newObject();
            event.put("ping", "ping");
            channel.write(event);
    	}
    }
    
    // Send a Json event to all members connected to this node
    public void notifyAll(String kind, String user, String text) {
        for(WebSocket.Out<JsonNode> channel: members.values()) {
            
            ObjectNode event = Json.newObject();
            event.put("kind", kind);
            event.put("user", user);
            event.put("message", text);
            
            ArrayNode m = event.putArray("members");
            
            //Go to Redis to read the full roster of members. Push it down with the message.
            Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
            try {
            	for(String u: j.smembers(roomMemberList)) {
            		m.add(u);
            	}
            } finally {
            	play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);            		   
            }
            
            channel.write(event);
        }
    }
    
    // -- Messages
    
    public static class Join {
        
        final String username;
        final WebSocket.Out<JsonNode> out;
        final WebSocket.In<JsonNode> in;
        
        public String getUsername() {
			return username;
		}
        public String getType() {
        	return "join";
        }

		public Join(String username, WebSocket.Out<JsonNode> out, WebSocket.In<JsonNode> in) {
            this.username = username;
            this.in = in;
            this.out = out;
        }
    }
    
    public static class RosterNotification {
    	
    	final String username;
    	final String direction;
    	
    	public String getUsername() {
    		return username;
    	}
    	public String getDirection() {
    		return direction;
    	}
    	public String getType() {
    		return "rosterNotify";
    	}
    	
    	public RosterNotification(String username, String direction) {
    		this.username = username;
    		this.direction = direction;
    	}
    }
    
    public static class Talk {
        
        final String username;
        final String text;
        
        public String getUsername() {
			return username;
		}
		public String getText() {
			return text;
		}
		public String getType() {
			return "talk";
		}

		public Talk(String username, String text) {
            this.username = username;
            this.text = text;
        }
        
    }
    
    public static class Quit {
        
        final String username;
        
        public String getUsername() {
			return username;
		}
        public String getType() {
        	return "quit";
        }

		public Quit(String username) {
            this.username = username;
        }
        
    }
    
    public static class Ping {        
        public String getType() {
        	return "ping";
        }
    }   
    
    public class ChatRoomListener extends JedisPubSub {
    	private final ActorRef chatroom;
    	
    	public ChatRoomListener(ActorRef chatroom){
    		super();
    		this.chatroom = chatroom;
    	}
    	
    	@Override
        public void onMessage(String channel, String messageBody) {
			//Process messages from the pub/sub channel
	    	JsonNode parsedMessage = Json.parse(messageBody);
	    	Object message = null;
	    	String messageType = parsedMessage.get("type").asText();
	    	if("talk".equals(messageType)) {	    		
	    		message = new Talk(
	    				parsedMessage.get("username").asText(), 
	    				parsedMessage.get("text").asText()
	    				);
	    	} else if("rosterNotify".equals(messageType)) {	
	    		message = new RosterNotification(
	    				parsedMessage.get("username").asText(),
	    				parsedMessage.get("direction").asText()
	    				);
	    	} else if("quit".equals(messageType)) {	
	    		message = new Quit(
	    				parsedMessage.get("username").asText() 
	    				);	    		
	    	}
			 chatroom.tell(message, null);	        
        }
		@Override
        public void onPMessage(String arg0, String arg1, String arg2) {
        }
		@Override
        public void onPSubscribe(String arg0, int arg1) {
        }
		@Override
        public void onPUnsubscribe(String arg0, int arg1) {
        }
		@Override
        public void onSubscribe(String arg0, int arg1) {
        }
		@Override
        public void onUnsubscribe(String arg0, int arg1) {
        }
    }
    
}
