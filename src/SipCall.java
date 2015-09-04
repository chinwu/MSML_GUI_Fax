import javax.sip.*;
import javax.sip.message.Request;

/*
 * A calls this app (AS)
 * AS calls B
 * 
 * 
 * One dialog will include multiple transactions. 
 * INVITE/OK/ACK is one transaction. BYE/OK is another transaction. etc
 * 
 */
public class SipCall 
{
	private Dialog dialog;
	private Request inviteRequest; //store this for use for RequestTeminated response when CANCEL is received
	
	private ServerTransaction st;
	private ClientTransaction ct;
	private Object sdp; //receiving sdp
	private String dialogid; 
	private String sessionVersion;		
	
	private SipCall theOtherCall;	
	public MediaState mediaState;	
	
	public SipCall()
	{
		dialog = null;
		st = null;
		ct = null;	
		sdp = null;
		theOtherCall = null;
		inviteRequest = null;
		
		mediaState = MediaState.IDLE;	
		mediaState.nextState = MediaState.IDLE;
		
	}
	public Dialog GetDialog()			{	return dialog;	}
	public void SetDialog(Dialog d) 	{	dialog = d;		}
	public Request GetInviteRequest()		{	return inviteRequest;	}
	public void SetInviteRequest(Request r)	{	inviteRequest = r;		}	
	public ServerTransaction GetServerTransaction()			{	return st;	}
	public void SetServerTransaction(ServerTransaction s)	{	st = s; 	}		
	public ClientTransaction GetClientTransaction()			{	return ct;	}
	public void SetClientTransaction(ClientTransaction c)	{	ct = c; 	}	
	public Object GetSdp()			{	return sdp; }
	public void SetSdp(Object s)	{	sdp = s;	}	
	
	public SipCall GetTheOtherCall()			{	return theOtherCall;	}
	public void SetTheOtherCall(SipCall other)	{	theOtherCall = other;	}	
	
	public String GetDialogid()				{	return dialogid;	}
	public void SetDialogid(String id) 		{	dialogid = id;		}
	
	
	public String ExtractSDPSessionVersion() //extract session version for reinvite. session change or timer refresh.
	{	
		if( sdp != null )
		{
			String sdpString = new String((byte[])sdp);
			String[] lines = sdpString.split("\n");
			for (String line : lines)
			{
				//GUI.println(timeStamp()+line);
				if(line.contains("o=")) 
				{				
					String[] subStrings = line.split(" ");				
					return subStrings[2];
				}
			}
		}
		return null;		
	}
}
