package atc.dispatchers;


import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import atc.atc.GameState;
import atc.messages.*;
import atc.util.ProducerConsumer;
import atc.util.SerializableInterface;

//import com.sun.jmx.remote.util.Service;

import net.sf.appia.jgcs.AppiaService;
import net.sf.jgcs.ClosedSessionException;
import net.sf.jgcs.DataSession;
import net.sf.jgcs.JGCSException;
import net.sf.jgcs.NotJoinedException;
import net.sf.jgcs.Service;
import net.sf.jgcs.UnsupportedServiceException;
import net.sf.jgcs.membership.BlockSession;

import org.apache.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

class Ticker extends TimerTask{
	private final SendDispatcher sender; 
	//When running sends tick to members of the game

	public Ticker(final SendDispatcher sender){
		this.sender = sender; 
	}
	
	public void run() {
		sender.send(new Tick()); 
	}
}

public  class SendDispatcher{
	private static  long TICK_RATE_MSS = 3000;
	public static void setTickRate(long rate) { TICK_RATE_MSS = rate ;}
	public static long getTickRate(){ return TICK_RATE_MSS ; }
	
	
	private BlockSession controlChannel;
	private DataSession dataChannel;
	private Service commandService; 
	private Service audioService; 
	private Service stateReconciliationService; 
	
	private Logger logger = Logger.getLogger(SendDispatcher.class); 
	private Lock  lock = new ReentrantLock(true); // acquire to do anything. Sequential behaviour for now.

	 //INVARIANT: when true no messages are send by this object.
	private Boolean can_send_messages; // Refers to the blocked state on view synchronous protocol. 
	 
	 //INVARIANT : If ticker is different from null the process is the current leader. 
	 Timer ticker= null ;   //Responsible for ticker thread. Sends tick.  
	
	 
	 protected SendDispatcher(BlockSession controlChannel, DataSession dataChannel, net.sf.jgcs.Service gameService, AppiaService reconcileService, AppiaService  chatService) {
		 this.controlChannel = controlChannel; 
		 this.dataChannel = dataChannel; 
		 this.commandService = gameService;
		 this.audioService = this.stateReconciliationService = gameService; 
		 //this.audioService = chatService; 
		 //this.stateReconciliationService = reconcileService; 
	 }
	 
	 public void send(Message cmd){
		 lock.lock();
		 try{
		 	if (can_send_messages) {
		 		broadcastMessage(cmd, commandService);
		 	}
		 }finally{
		 	lock.unlock();
		}
	 }
	 
	 public void block(){
		 ////logger.info("going to block()...");
		 lock.lock();
		 try{
		 		can_send_messages = false;
		 		controlChannel.blockOk(); // tell appia we shut up.
		 
		 		// shutdown possible ticker
		 		if (ticker != null){ 
		 			ticker.cancel(); 
		 		}
		 		ticker = null;
		 		
		 } catch (NotJoinedException e) {
			// TODO Auto-generated catch block
			 e.printStackTrace();
		} catch (JGCSException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		 finally{
			 lock.unlock();
		 }
		 
		 ////logger.info("...sended block()"); 
	 }
	 
	 /**
	  * Inform new membership. 
	  * From now on only ticks messages are received. No messages are sended.
	  * As soon as the tick messages 
	  * @param state
	  */

	 public void membershipChange(StateMessage msg){
		//logger.info("membershipChange warning...");
		try {
			//Check to see if we are the leader
			// TODO - use the same membership
			//TODO - think about deadlocks on this. Other thread invoking membership change at the same time.
			int coordinator_rank, local_rank;
			//logger.info("starting synced access to membership ranks to detect if we are the leader..."); 
			synchronized(controlChannel){
				coordinator_rank = controlChannel.getMembership().getCoordinatorRank(); 
				local_rank = controlChannel.getMembership().getLocalRank(); 
				logger.info("New membership : " + controlChannel.getMembership().getMembershipList()); 
			}
			if (coordinator_rank == local_rank){
				CreateTick(); 
			}
		} catch (NotJoinedException e) {
			//logger.fatal("Could not check membership"); 
			return ; 
		}
		//logger.info("..finished synced access to membership ranks"  ); 
		lock.lock();
		 try{
			 //TODO - clean me up scotty. broadcastMessage checks can_send_messages 
			 //We can not send messages until we receive all states. 
			 can_send_messages = true; 
			 logger.info(stateReconciliationService); 
			 broadcastMessage(msg, this.stateReconciliationService); 
			 can_send_messages = false; 
		 }finally{
			 lock.unlock();
		 }
		//logger.info("... finished membership change broadcast of state"); 
	 }
	 
	 public void unBlock(){
		 //logger.info("going to unblock the condition variable");
		 lock.lock(); 
		 try{
			 can_send_messages = true;
		 }
		 finally{
			 lock.unlock();
		 }
		 //logger.info("unblocked");
	 }

	 	 /*
	 	  * Use this method to activate the leader mode in process. 
	 	  */
	 	public  void CreateTick(){
	 		//logger.info("Creating ticker...");
	 		logger.info("I am the ONE! (being leader)"); 
	 		//System.err.println("being leader"); 
	 		ticker = new Timer();
	 		ticker.scheduleAtFixedRate(new Ticker(this), SendDispatcher.TICK_RATE_MSS, SendDispatcher.TICK_RATE_MSS); 
	 		//logger.info("... Ticker created"); 
	 	}
	 	
	 private void  broadcastMessage(final Message command, Service s){
		 net.sf.jgcs.Message m;
		 byte[] payload;
		 
		 try {
			lock.lock();
				if (!can_send_messages) {
					return; // Discards all command messages when in blocked state.   
				}
				
			m = dataChannel.createMessage();			
			payload = SerializableInterface.objectToByte(command);
			m.setPayload(payload);
			//logger.info("Sending message:" + command.toString() + "..."); 
			dataChannel.multicast(m, s, null, (net.sf.jgcs.Annotation[]) null);
		} catch (Exception e){
			System.err.println(e.getMessage()); 
			e.printStackTrace(System.err);
		}finally{
			lock.unlock(); 
		}
		//logger.info("...message sent"); 
	 }
	 
    }
