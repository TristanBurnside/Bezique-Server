package controllers;

import models.ChatRoom;
import play.api.Play;
import play.api.data.Forms;
import play.data.DynamicForm;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.WebSocket;
import scala.App;
import views.html.chatRoom;
import views.html.index;

import com.fasterxml.jackson.databind.JsonNode;

public class Application extends Controller {
  
    /**
     * Display the home page.
     */
    public static Result index() {
        return ok(index.render());
    }
  
    /**
     * Display the chat room.
     */
    public static Result chatRoom() {
    	DynamicForm requestData = Form.form().bindFromRequest();
        String username = requestData.get("username");
        String roomname = requestData.get("roomname");
        if(username == null || username.trim().equals("") || roomname == null || roomname.trim().equals("")) {
            flash("error", "Please choose a valid username and room name. You chose:" + username + " and " + roomname);
            return redirect(routes.Application.index());
        }
        
        return ok(chatRoom.render(username, roomname));
    }

    public static Result chatRoomJs(String username, String roomName) {
        return ok(views.js.chatRoom.render(username, roomName));
    }
    
    /**
     * Handle the chat websocket.
     */
    public static WebSocket<JsonNode> chat(final String username, final String chatroom) {
        return new WebSocket<JsonNode>() {
            
            // Called when the Websocket Handshake is done.
            public void onReady(WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out){
                
                // Join the chat room.
                try { 
                    ChatRoom.join(username, Integer.valueOf(chatroom), in, out);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };
    }
  
}
