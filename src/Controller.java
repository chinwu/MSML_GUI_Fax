
import XmsMsml.*;
import XmsMsml.Msml.Dialogend;
import XmsMsml.Msml.Dialogstart;
import XmsMsml.Transfer.Fileobj;
import gov.nist.javax.sip.header.CSeq;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
//this app has evolved from a voice app to a fax app. Read the last section of this comment for this app. 

/* 		3PCC for MSML test
 * 
 * ----
 *  3PCC: Using SIP JAIN stack
 * 1. UA A calls this Application Server (AS).
 * 2. AS calls UA B (XMS).
 * 3. AS passes SDP between A and B. AS does not involve media.
 * 4. UA A disconnects the call to AS. 
 * 5. AS disconnects the call to UA B. 
 * 
 * 2. is implemented in CreateINVITE().
 * If caller selects IVR option to disconnect the call, AS will send BYE to B and A.
 * 
 * A SipCall class helps to track the SIP dialog, MSML media state.
 * More importantly, it helps to track the other SIP call in one 3PCC call. 
 * This is particularly helpful when testing multiple 3PCC calls simultaneously.
 * 
 * ----
 *   MSML:
 * To make msml marshal/un-marshal easier, I use Dialogstart src attribute to ask XMS to use the script specified in src attribute.
 * I also include using marshal/un-marshal for msml elements. They are in comment sections as your reference.
 * 
 * I use the moml scripts and audio wave files from MSML application server sample from Dialogic web site.
 * 
 * To keep tracks of media states (like main menu, recording, playback, disconnect), I create a new enum class MediaState. 
 * This class helps to track current state and next state. This class also specifies where the script files are located.
 * This class reduce the complexity of modifying the IVR call flow.
 * The script and media wave files are located in another machine (192.219.76.191 32bit/windows7/Pentium4 machine running Tomcat).
 * 
 * Current AS call flow:
 * Play main menu. Select "1" to record, "2" to playback, "*" to disconnect call.
 * When done with 1, 2 or invalid dtmf, call goes back to main menu.
 * When select "*", AS disconnects call to B and from A.
 * 
 */

/*		Inbound Fax
 * ----
 * 		To simplify the implementation and troubleshooting, this app only handles inbound fax
 * Call flow: 
 * 		Sr140 (fax over IP T38 device) -> this AS -> XMS
 * SIP flow:
 * 		Sr140 				AS					XMS
 * 			->invite(sdp)
 * 								->invite(sdp) 
 * 								<-OK(sdp)
 * 			<-OK(sdp)
 * 			->ACK
 * 								->ACK
 * 								->INFO(CNG detect, optinal)
 * 								<-INFO(CNG detected)
 * 								->INFO(faxreceive)
 * 								<-invite(t38)
 * 			<-invite(t38)
 * 			->OK(t38) or 488(for g711 fax passthru)			
 * 								->OK(t38) or 488 (g711)
 * 								<-ack
 * 			<-ack
 *			<---T38 start between Sr140 and XMS---> 
 *								<-INFO(fax.negotiate event)
 *								<-INFO(fax.pagedone, fax.objextdone, ect)
 *			<---T38 end--------------------------->
 *								<-INFO(fax.opcomplete)
 *								<-INFO(msml.dialog.exit)
 *								->BYE
 *			<-BYE
 */
public class Controller implements SipListener 
{
	private static StringWriter sw = new StringWriter();
	
	private static String myIpAddress = GUI.textLocalAddr.getText();
	private static String myUser = GUI.textLocalUser.getText();
	private static int myPort = Integer.parseInt(GUI.textLocalPort.getText());
	private static String ipAddressB = GUI.textXmsAddr.getText();
	private static int portB = Integer.parseInt(GUI.textXmsPort.getText());
	private static String userB = GUI.txtXmsUser.getText();	
	private static String sessionTimerFresher = "uas";			
	
	private static SipFactory sipFactory;
	private static SipStack sipStack;
	private static SipProvider sipProvider;
	private static HeaderFactory headerFactory;
	private static MessageFactory messageFactory;
	private static AddressFactory addressFactory;
	
	private Map<Dialog, SipCall> callMap; 
	
	private ObjectFactory objectFactory;
	private JAXBContext jaxbContext;
	private Marshaller marshaller;
	private Unmarshaller unmarshaller;
	
	public class MyTimer extends TimerTask
	{
		Controller _controller;
		SipCall _call;
		public MyTimer(Controller controller, SipCall call)
		{
			_controller = controller;
			_call = call;
		}
		public void run()
		{
			if(!callMap.containsValue(_call)) return;
			_controller.CreateReInvite(_call);
		}
	}
	public class MyTimerDialogend extends TimerTask
	{
		Controller _controller;
		SipCall _call;
		public MyTimerDialogend(Controller controller, SipCall call)
		{
			_controller = controller;
			_call = call;
		}
		public void run()
		{
			if(!callMap.containsValue(_call)) return;
			_controller.CreateInfoDialogend(_call);
		}
	}

	public String timeStamp()
	{
		return new SimpleDateFormat("[mm:ss.SSS] ").format(Calendar.getInstance().getTime());
	}
	public String TagGenerator()
	{
		return Integer.toHexString(new Random().nextInt(0xffffff)+0xffffff);
	}

	public void init()
	{
		myIpAddress = GUI.textLocalAddr.getText();
		myUser = GUI.textLocalUser.getText();
		myPort = Integer.parseInt(GUI.textLocalPort.getText());
		ipAddressB = GUI.textXmsAddr.getText();
		portB = Integer.parseInt(GUI.textXmsPort.getText());
		userB = GUI.txtXmsUser.getText();	
		
		callMap = new HashMap<Dialog, SipCall>();
		
		sipFactory = SipFactory.getInstance();
		sipFactory.setPathName("gov.nist");
		
		Properties properties = new Properties();
		properties.setProperty("javax.sip.STACK_NAME", "Controller");
		properties.setProperty("javax.sip.IP_ADDRESS", myIpAddress);		
		properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "32");
		properties.setProperty("gov.nist.javax.sip.SERVER_LOG", "Controller.txt");
		properties.setProperty("gov.nist.javax.sip.DEBUG_LOG", "Controller.log");		
		try
		{
			sipStack = sipFactory.createSipStack(properties);
			GUI.println(timeStamp()+"sipStack created -> " + sipStack);
			
			ListeningPoint lp = sipStack.createListeningPoint(myIpAddress, myPort, "udp");
			sipProvider = sipStack.createSipProvider(lp);
			GUI.println(timeStamp()+"sipProvider created -> " + sipProvider);
			
			Controller listener = this; 
			sipProvider.addSipListener(listener);
			
			headerFactory = sipFactory.createHeaderFactory();
			messageFactory = sipFactory.createMessageFactory();
			addressFactory = sipFactory.createAddressFactory();
			
		}
		catch (Exception e)
		{
			e.printStackTrace(new PrintWriter(sw));
			GUI.println(sw.toString());
			GUI.btnStart.setEnabled(true);
		
		}
		//MSML
		try
		{
			objectFactory = new ObjectFactory();
			jaxbContext = JAXBContext.newInstance(Msml.class);
			marshaller = jaxbContext.createMarshaller();
			unmarshaller = jaxbContext.createUnmarshaller();
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw));
			GUI.println(sw.toString());
		}		
	}
	@Override
	public void processRequest(RequestEvent requestEvent) 
	{
		// update server transaction here
		Request request = requestEvent.getRequest(); 
		ServerTransaction  st = requestEvent.getServerTransaction();
		GUI.println(timeStamp()+"requestEvent received -> " + request.getMethod() );
		try	{	if(st == null) st = sipProvider.getNewServerTransaction(request);	}
		catch(Exception e)	{	e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());	}	
		SipCall call=null;
		switch(request.getMethod())
		{
		case Request.INVITE:
			if(callMap.containsKey(st.getDialog())) //existing dialog : it is a re-invite
			{				
				call = callMap.get(st.getDialog());
				if( call.ExtractSDPSessionVersion().equals(GetSessionVersion(request.getContent()))) //same version no change in sdp
				{ //session timer
					call.SetServerTransaction(st);
					DoReInviteOk(call, request);
				}
				else //update sdp
				{
					if(request.getContent()!=null)
					{
						call.SetSdp(request.getContent());
					}
					call.SetServerTransaction(st);				
					CreateReInviteFax(call.GetTheOtherCall());  
				}
			}
			else //new call
			{					
				call = new SipCall();
				call.SetInviteRequest(request);
				call.SetServerTransaction(st);
				call.SetDialog(st.getDialog());	
				GUI.println("dialog1="+st.getDialog());
				callMap.put(st.getDialog(), call);
				DoInviteResponse(request, call);
			}
			break;
		case Request.BYE:			
			call = callMap.get(st.getDialog());
			CreateBye(call.GetTheOtherCall());
			DoByeOk(requestEvent);
			break;
		case Request.ACK:			
			break;
		case Request.CANCEL:
			call = callMap.get(st.getDialog());
			CreateCancel(call.GetTheOtherCall());
			DoCancelOk(requestEvent);
			DoRequestTerminatedResponse(call.GetInviteRequest(), call);
			break;
		case Request.INFO:
			call = callMap.get(st.getDialog());
			 DoInfoOk(requestEvent);
			 
			 if( request.getContent() != null)
			 {
				 Msml msml = GetInfoAndOkSdp(new ByteArrayInputStream((byte[]) request.getContent()));
				 GUI.println(timeStamp()+"received Info with event -> " + msml.getEvent().getName());
//				 if( GUI.rdbtnDialogendAtEvent.isSelected())
//					 if( msml.getEvent().getName().equals(GUI.txtEndEvent.getText()))
//						 CreateInfoDialogend(call);
				 List<JAXBElement<String>> nameValue = msml.getEvent().getNameAndValue();
				 if( nameValue.size() > 0 )
				 {
					 for(int i=0; i< nameValue.size(); i+=2 )
					 {
						 GUI.println(timeStamp()+"Name:Value -> " + nameValue.get(i).getValue() + ":" + nameValue.get(i+1).getValue());
						 if(nameValue.get(i).getValue().equals("faxdetect.tone") )
						 {					 
							 switch (nameValue.get(i+1).getValue())
							 {
							 case "CNG": //next state recording
								 call.mediaState.nextState = MediaState.RECORDING;
								 break;							
							 default:
								 call.mediaState.nextState = MediaState.TERMINATE;
								 break;											 
							 }
							 break;
						 }
						 if(nameValue.get(i).getValue().equals("faxdetect.end"))
						 {
							 if(!nameValue.get(i+1).getValue().equals("faxdetect.complete"))
								 call.mediaState.nextState = MediaState.TERMINATE;
						 }						
					 }
				 }
				 if (msml.getEvent().getName().equals("msml.dialog.exit"))
				 {					 
					 if( nameValue.size() > 0 )
					 {
						 if( nameValue.get(0).getValue().equals("dialog.exit.status"))
						 {
							 if( nameValue.get(1).getValue().startsWith("4"))
							 {
								 GUI.println(timeStamp()+"Received 400 response. Sending BYE.");
								 //call.mediaState = MediaState.INVALID;								
								 CreateBye(call);
								 CreateBye(call.GetTheOtherCall());
							 }							 
						 }
					 }
					 else						 
					 {	
						 GUI.println(timeStamp()+"nextState == " + call.mediaState.nextState);							
						
						 if( call.mediaState.nextState != MediaState.TERMINATE)
						 {
							call.mediaState = call.mediaState.nextState;
							CreateInfo(call);	
							call.mediaState.nextState = MediaState.TERMINATE;	//make sure no more script or not to set next state to terminate
						 }
						 else
						 {
							 CreateBye(call);
							 CreateBye(call.GetTheOtherCall());
							 GUI.println(timeStamp()+"Sending BYE");
						 }						 
					 }
				}
			 }
			break;		
		}				
	}
	
	@Override
	public void processResponse(ResponseEvent responseEvent) 
	{
		// update client transaction here
		Response response = responseEvent.getResponse();
		CSeqHeader cSeq = (CSeqHeader) response.getHeader(CSeq.NAME);		
		ClientTransaction  ct = responseEvent.getClientTransaction();
		SipCall call = new SipCall();
		if(response.getContent()!=null)	call.SetSdp(response.getContent());
		call = callMap.get(ct.getDialog());
		call.SetClientTransaction(ct);
		call.SetDialog(ct.getDialog());		
				
		GUI.println(timeStamp()+"clientEvent received -> " + response.getStatusCode());// + "\nreceived at -> " + sipStack.getStackName() + "\nclient transaction -> " + ct);
		switch(response.getStatusCode())
		{
		case Response.OK:
			switch(cSeq.getMethod())
			{
			case Request.INVITE:
				if(call.mediaState==MediaState.IDLE) //initial connection
				{
					call.SetSdp(response.getContent());
					DoInviteOk(call.GetTheOtherCall(), responseEvent); //answer the other call				
					DoOkAck(call); //Ack this call
					if( !GUI.txtGreeting.getText().trim().equals("") ) //greeting state script present, start with greeting state
					{
						call.mediaState = MediaState.GREETING;
						call.GetTheOtherCall().mediaState =  MediaState.GREETING;
					}
					else //no greeting state, start with recording state (Faxreceive)
					{
						call.mediaState = MediaState.RECORDING;
						call.GetTheOtherCall().mediaState =  MediaState.RECORDING;
						call.mediaState.nextState = MediaState.TERMINATE;
					}
					CreateInfo(call); //Info this call for greeting after connect
					//a timer thread to send reINVITE for session timer refresh
//					if(sessionTimerFresher.equals("uac"))
//						new Timer().schedule(new MyTimer(this, call), 45000);  
				}
				else //re-Invite
				{
//					new Timer().schedule(new MyTimer(this, call), 45000); //session timer
					call.SetSdp(response.getContent());
					DoReInviteFaxOk(call.GetTheOtherCall()); //answer the other call	
					DoOkAck(call); //Ack this call
				}				
				break;
			case Request.INFO:
				if(response.getContent()!=null)
				{				
					Msml msml = GetInfoAndOkSdp(new ByteArrayInputStream((byte[]) response.getContent()));					
					GUI.println(timeStamp()+"recieved OK for Info -> response=" + msml.getResult().getResponse() + 
							", dialogid=" + msml.getConfidOrDialogid().get(0).getValue());
								
					call.SetDialogid(msml.getConfidOrDialogid().get(0).getValue());
//					if( GUI.rdbtnDialogend.isSelected()) //test dialogend
//					{
//						new Timer().schedule(new MyTimerDialogend(this, call), Integer.parseInt(GUI.txtDialogendTimer.getText())); 
//					}
				}				
				break;
			}			
			break;
		case Response.REQUEST_TERMINATED:
			DoRequestTerminatedAck(call);
			callMap.remove(call.GetDialog());
			GUI.println(timeStamp()+"hashmap size -> " + callMap.size());
			break;
		case Response.NOT_ACCEPTABLE_HERE:
			GUI.println(timeStamp()+"recieved 488 for INVITE");
			DoInviteResponse488(call.GetTheOtherCall());			
			break;
		}	
	}
	@Override
	public void processDialogTerminated(DialogTerminatedEvent evt) 
	{
		//GUI.println(timeStamp()+"-> Dialog Terminated");		
	}
	@Override
	public void processIOException(IOExceptionEvent arg0) 
	{
		GUI.println(timeStamp()+"-> IO Exception");	
	}
	@Override
	public void processTimeout(TimeoutEvent evt) 
	{
		GUI.println(timeStamp()+"-> Process timeout");		
	}
	@Override
	public void processTransactionTerminated(TransactionTerminatedEvent arg0) 
	{
		//GUI.println(timeStamp()+"-> Transaction Terminated");		
	}

	public void DoInviteResponse(Request request, SipCall call)
	{	
		try
		{
			Response ringResponse = messageFactory.createResponse(Response.RINGING, request);
			ToHeader toHeader = (ToHeader)ringResponse.getHeader(ToHeader.NAME);
			toHeader.setTag(Integer.toHexString(new Random().nextInt(0xffffff)+0xffffff));			
			
			call.GetServerTransaction().sendResponse(ringResponse);
			
			Object sdp = request.getContent();
			call.SetSdp(sdp);
			
			CreateInvite(call);	//create a new call to B using call A, since call B not available yet				
		}
		catch(Exception e)
		{
			e.getStackTrace();
		}
	}
	public void DoInviteResponse488(SipCall call)
	{	
		try
		{
			Response notAcceptableResponse = messageFactory.createResponse(Response.NOT_ACCEPTABLE_HERE, call.GetServerTransaction().getRequest());
						
			call.GetServerTransaction().sendResponse(notAcceptableResponse);
							
		}
		catch(Exception e)
		{
			e.getStackTrace();
		}
	}
	public void DoByeOk(RequestEvent requestEvent)
	{
		Request request = requestEvent.getRequest();
		ServerTransaction st = requestEvent.getServerTransaction();
		
		GUI.println(timeStamp()+"Received Bye, sending OK");
		try
		{
			Response okResponse = messageFactory.createResponse(Response.OK, request);
			st.sendResponse(okResponse);
		}
		catch(Exception e)
		{
			e.getStackTrace();
		}		
		callMap.remove(st.getDialog());
		GUI.println(timeStamp()+"hashmap size -> " + callMap.size());
	}
	public void DoCancelOk(RequestEvent requestEvent)
	{
		Request request = requestEvent.getRequest();
		ServerTransaction st = requestEvent.getServerTransaction();
		
		GUI.println(timeStamp()+"Received Cancel, sending OK");
		try
		{
			Response okResponse = messageFactory.createResponse(Response.OK, request);
			st.sendResponse(okResponse);
		}
		catch(Exception e)
		{
			e.getStackTrace();
		}		
	}
	public void DoRequestTerminatedResponse(Request request, SipCall call)
	{
				
		GUI.println(timeStamp()+"Received Cancel, sending request terminated");
		try
		{
			Response requestTerminatedResponse = messageFactory.createResponse(Response.REQUEST_TERMINATED, request);
			call.GetServerTransaction().sendResponse(requestTerminatedResponse);
		}
		catch(Exception e)
		{
			e.getStackTrace();
		}		
		callMap.remove(call.GetDialog());
		GUI.println(timeStamp()+"hashmap size -> " + callMap.size());
	}
	
	public void DoOkAck(SipCall call)
	{
		try
		{
			Request request = call.GetClientTransaction().getRequest();
			Request ackRequest = call.GetDialog().createAck(((CSeqHeader)request.getHeader(CSeqHeader.NAME)).getSeqNumber());
			call.GetDialog().sendAck(ackRequest);
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
	}
	public void DoRequestTerminatedAck(SipCall call)
	{
		try
		{
			Request request = call.GetClientTransaction().getRequest();
			Request ackRequest = call.GetDialog().createAck(((CSeqHeader)request.getHeader(CSeqHeader.NAME)).getSeqNumber());
			call.GetDialog().sendAck(ackRequest);
			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw));GUI.println(sw.toString());
		}
	}
	public void CreateInvite(SipCall other)
	{
		try
		{
			// create SipUri, Address, then Header
			SipURI requestUri = addressFactory.createSipURI(userB, ipAddressB + ":" + portB);
			
			SipURI fromUri =  addressFactory.createSipURI(myUser, myIpAddress);			
			Address fromAddress = addressFactory.createAddress(fromUri);			
			FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, TagGenerator());
			
			SipURI toUri = addressFactory.createSipURI(userB, ipAddressB);
			Address toAddress = addressFactory.createAddress(toUri);
			ToHeader toHeader = headerFactory.createToHeader(toAddress, null);
			
			ArrayList<ViaHeader> viaHeaders = new ArrayList<ViaHeader>();
			ViaHeader viaHeader = headerFactory.createViaHeader(myIpAddress, sipProvider.getListeningPoint("udp").getPort(), "udp", null);
			viaHeaders.add(viaHeader);
			
			CallIdHeader callIdHeader = sipProvider.getNewCallId();	
			//PAssertedIdentityHeader pAssertedIdHeader = headerFactory.
			
			CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.INVITE);		
			
			ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
			
			MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);			
			//Use original SIP call's SDP for this new SIP call
			Request request = messageFactory.createRequest(requestUri, Request.INVITE, callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwardsHeader, contentTypeHeader, other.GetSdp());
			
			//adding session timer support to xms
//			if(GUI.rdbtnSessionTimer.isSelected())
//			{
//				Header supportedHeader = headerFactory.createHeader("Supported", "timer");
//				Header requireHeader = headerFactory.createHeader("Require", "timer");
//				Header sessionExpires = headerFactory.createHeader("Session-Expires", GUI.txtSessionExpires.getText()+";refresher=" + sessionTimerFresher);
//				Header minSE = headerFactory.createHeader("Min-SE", "90");
//				request.addHeader(supportedHeader);
//				request.addHeader(requireHeader);
//				request.addHeader(sessionExpires);
//				request.addHeader(minSE);
//			}
			
			SipURI contactUri = addressFactory.createSipURI(myUser, myIpAddress);
			contactUri.setPort(sipProvider.getListeningPoint("udp").getPort());
			Address contactAddress = addressFactory.createAddress(contactUri);
			ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
			request.addHeader(contactHeader);
			
			Header acceptContactHeader = headerFactory.createHeader("Accept-Contact", "*;+sip.fax=\"t38\"");
			request.addHeader(acceptContactHeader);
			
			SipCall call = new SipCall();			
			other.SetTheOtherCall(call);
			call.SetTheOtherCall(other);
			ClientTransaction ct = sipProvider.getNewClientTransaction(request);			
			call.SetClientTransaction(ct);			
			call.SetDialog(ct.getDialog());
			GUI.println("dialog2="+ct.getDialog());
			callMap.put(ct.getDialog(), call);
			GUI.println(timeStamp()+"Send INVITE");
			ct.sendRequest();			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
		
	}
	public void DoInviteOk(SipCall call, ResponseEvent responseEvent)
	{
		try
		{
		Response okResponse = messageFactory.createResponse(Response.OK, call.GetServerTransaction().getRequest());
		
		Address contactAddress = addressFactory.createAddress("<sip:" + myIpAddress + ">");
		ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
		okResponse.addHeader(contactHeader);			
		
		MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
		okResponse.addHeader(maxForwardsHeader);			
		
		AllowHeader allowHeader = headerFactory.createAllowHeader("INVITE, BYE, ACK, CANCEL, OPTIONS, INFO");
		okResponse.addHeader(allowHeader);			
		 // Create ContentTypeHeader
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
       
        okResponse.setContent(call.GetTheOtherCall().GetSdp(), contentTypeHeader);
        call.GetServerTransaction().sendResponse(okResponse);
		}
		catch (Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}		
	}
	public void DoReInviteFaxOk(SipCall call)
	{
		try
		{
		Response okResponse = messageFactory.createResponse(Response.OK, call.GetServerTransaction().getRequest());
		
		Address contactAddress = addressFactory.createAddress("<sip:" + myIpAddress + ">");
		ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
		okResponse.addHeader(contactHeader);			
		
		MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
		okResponse.addHeader(maxForwardsHeader);			
		
		AllowHeader allowHeader = headerFactory.createAllowHeader("INVITE, BYE, ACK, CANCEL, OPTIONS, INFO");
		okResponse.addHeader(allowHeader);			
		 // Create ContentTypeHeader
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
       
        okResponse.setContent(call.GetTheOtherCall().GetSdp(), contentTypeHeader);
        call.GetServerTransaction().sendResponse(okResponse);
		}
		catch (Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
	}
	public void DoReInviteFaxOkPort0(SipCall call) //send 
	{
		try
		{
		Response okResponse = messageFactory.createResponse(Response.OK, call.GetServerTransaction().getRequest());
		
		Address contactAddress = addressFactory.createAddress("<sip:" + myIpAddress + ">");
		ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
		okResponse.addHeader(contactHeader);			
		
		MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
		okResponse.addHeader(maxForwardsHeader);			
		
		AllowHeader allowHeader = headerFactory.createAllowHeader("INVITE, BYE, ACK, CANCEL, OPTIONS, INFO");
		okResponse.addHeader(allowHeader);			
		 // Create ContentTypeHeader
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
       
    	
        okResponse.setContent(SetMediaPort0(call), contentTypeHeader);
        call.GetServerTransaction().sendResponse(okResponse);
		}
		catch (Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
	}
	public Object SetMediaPort0(SipCall call)
	{		
		String sdpString = new String((byte[])call.GetSdp());
		String newSdp;
//		String theOtherSdpString = new String((byte[])call.GetTheOtherCall().GetSdp());
//		String[] lines = sdpString.split("\n");
//		for (String line : lines)
//		{
//			//GUI.println(timeStamp()+line);
//			if(line.contains("m=image")) 
//			{				
//				String[] subStrings = line.split(" ");				
//				return subStrings[2];
//			}
//		}
		
//		String cLineRegex = "c=.*[\r\n]";
//		String cLine = Pattern.compile(cLineRegex,Pattern.DOTALL).matcher(theOtherSdpString).
//		GUI.println("Old C Line -> " + cLine);
		
		String regex = "m=image [0-9]+ ";
		newSdp=Pattern.compile(regex,Pattern.DOTALL).matcher(sdpString).replaceAll("m=image 0 ");
		
		GUI.println(newSdp);
		return (Object)newSdp.getBytes();
		
	}
	public void CreateBye(SipCall call)
	{
		if( call != null)
		{
			try 
			{				
	            Request byeRequest = call.GetDialog().createRequest(Request.BYE);
	            ClientTransaction ct = sipProvider.getNewClientTransaction(byeRequest);
	            call.GetDialog().sendRequest(ct);
	        } 
			catch (Exception ex) 
	        {
	            ex.printStackTrace();
	        }
			callMap.remove(call.GetDialog());
			GUI.println(timeStamp()+"hashmap size -> " + callMap.size());
		}
	}public void CreateCancel(SipCall call)
	{
		if( call != null)
		{
			try 
			{				
				Request cancelRequest = call.GetClientTransaction().createCancel();
				ClientTransaction ct = sipProvider.getNewClientTransaction(cancelRequest);				
				ct.sendRequest();
	        } 
			catch (Exception ex) 
	        {
	            ex.printStackTrace();
	        }
		}
	}

	public void CreateInfo(SipCall call)
	{
		try
		{
			GUI.println(timeStamp()+"Sending Info in -> " + call.mediaState.toString());
			Request infoRequest = call.GetDialog().createRequest(Request.INFO);			
			
			ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application","xml");
			infoRequest.addHeader(contentTypeHeader);
			infoRequest.setContent(CreateInfoDialogstartSdp(call), contentTypeHeader);
			ClientTransaction ct = sipProvider.getNewClientTransaction(infoRequest);
			call.GetDialog().sendRequest(ct);
			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}		
	}	
	public void CreateReInviteFax(SipCall call)
	{
		try
		{
			GUI.println(timeStamp()+"Sending ReInvite for Fax");
			Request reInviteFaxRequest = call.GetDialog().createRequest(Request.INVITE);	
			
			ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application","sdp");
			reInviteFaxRequest.addHeader(contentTypeHeader);
			reInviteFaxRequest.setContent(call.GetTheOtherCall().GetSdp(), contentTypeHeader);
			
			ClientTransaction ct = sipProvider.getNewClientTransaction(reInviteFaxRequest);
			call.GetDialog().sendRequest(ct);
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}			
	}
	public void CreateReInvite(SipCall call)
	{
		try
		{
			GUI.println(timeStamp()+"Sending ReInvite");
			Request reInviteRequest = call.GetDialog().createRequest(Request.INVITE);	
			Header supportedHeader = headerFactory.createHeader("Supported", "timer");
			Header requireHeader = headerFactory.createHeader("Require", "timer");
			Header sessionExpires = headerFactory.createHeader("Session-Expires", "90;refresher=uac");
			Header minSE = headerFactory.createHeader("Min-SE", "90");
			reInviteRequest.addHeader(supportedHeader);
			reInviteRequest.addHeader(requireHeader);
			reInviteRequest.addHeader(sessionExpires);
			reInviteRequest.addHeader(minSE);
			
			
			ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application","sdp");
			reInviteRequest.addHeader(contentTypeHeader);

			reInviteRequest.setContent(call.GetTheOtherCall().GetSdp(), contentTypeHeader);
			ClientTransaction ct = sipProvider.getNewClientTransaction(reInviteRequest);
			call.GetDialog().sendRequest(ct);
			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}		
	}
	public void DoReInviteOk(SipCall call, Request request)
	{
		try
		{
		Response okResponse = messageFactory.createResponse(Response.OK, request);
		SupportedHeader supportedHeader = headerFactory.createSupportedHeader("timer");
		okResponse.addHeader(supportedHeader);
		Header sessionExpiresHeader =  request.getHeader("Session-Expires");
		okResponse.addHeader(sessionExpiresHeader);
		Address contactAddress = addressFactory.createAddress("<sip:" + myIpAddress + ">");
		ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
		okResponse.addHeader(contactHeader);			
		
		MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
		okResponse.addHeader(maxForwardsHeader);			
		
		AllowHeader allowHeader = headerFactory.createAllowHeader("INVITE, BYE, ACK, CANCEL, OPTIONS, INFO");
		okResponse.addHeader(allowHeader);			
		 // Create ContentTypeHeader
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
       
        okResponse.setContent(call.GetTheOtherCall().GetSdp(), contentTypeHeader);
        call.GetServerTransaction().sendResponse(okResponse);
		}
		catch (Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}		
	}
	
	public void DoInfoOk(RequestEvent requestEvent)
	{
		Request request = requestEvent.getRequest();
		ServerTransaction st = requestEvent.getServerTransaction();
		
		GUI.println(timeStamp()+"Received Info, sending OK");
		try
		{
			Response okResponse = messageFactory.createResponse(Response.OK, request);
			st.sendResponse(okResponse);
		}
		catch(Exception e)
		{
			e.getStackTrace();
		}	
	}	
	//Only Dialogstart element. This is to use with a script
	public ByteArrayOutputStream CreateInfoDialogstartSdp(SipCall call) 
	{
		ByteArrayOutputStream byteArayStream = new ByteArrayOutputStream();
		//create each element
		Msml msml = objectFactory.createMsml();		
		Dialogstart dialogstart = objectFactory.createMsmlDialogstart();		
		//set individual attributes
		msml.setVersion("1.1");
		dialogstart.setTarget("conn:" + call.GetDialog().getRemoteTag());
		dialogstart.setType(DialogLanguageDatatype.APPLICATION_MOML_XML);	
		GUI.println(timeStamp()+"moml script source -> " + call.mediaState.GetScriptSource());
		dialogstart.setSrc(call.mediaState.GetScriptSource()); //get script source defined in MediaState.java		
		//link all		
		msml.getMsmlRequest().add(dialogstart);	
		
		//marshal
		try
		{
			marshaller.marshal(msml, byteArayStream);			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
		return byteArayStream;		
	}
	public ByteArrayOutputStream CreateInfoDialogendSdp(SipCall call) 
	{
		ByteArrayOutputStream byteArayStream = new ByteArrayOutputStream();
		//create each element
		Msml msml = objectFactory.createMsml();		
		Dialogend dialogend = objectFactory.createMsmlDialogend();
		
		//set individual attributes
		msml.setVersion("1.1");
		dialogend.setId(call.GetDialogid());		
		
		GUI.println(timeStamp()+"Send dialogend ");
		
		//link all		
		msml.getMsmlRequest().add(dialogend);	
		
		//marshal
		try
		{
			marshaller.marshal(msml, byteArayStream);			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
		return byteArayStream;		
	}
	public void CreateInfoDialogend(SipCall call)
	{
		try
		{
			GUI.println(timeStamp()+"Sending Info in -> " + call.mediaState.toString());
			Request infoRequest = call.GetDialog().createRequest(Request.INFO);			
			ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application","xml");
			infoRequest.addHeader(contentTypeHeader);
			infoRequest.setContent(CreateInfoDialogendSdp(call), contentTypeHeader);
			ClientTransaction ct = sipProvider.getNewClientTransaction(infoRequest);
			call.GetDialog().sendRequest(ct);
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}		
	}	
	public ByteArrayOutputStream CreateInfoTransferSdp(SipCall call) 
	{
		ByteArrayOutputStream byteArayStream = new ByteArrayOutputStream();
		//create each element
		Msml msml = objectFactory.createMsml();		
		Dialogstart dialogstart = objectFactory.createMsmlDialogstart();
		
		//set individual attributes
		msml.setVersion("1.1");
		dialogstart.setTarget("conn:" + call.GetDialog().getRemoteTag());
		dialogstart.setType(DialogLanguageDatatype.APPLICATION_MOML_XML);	
		Transfer transfer = objectFactory.createTransfer();
		Fileobj fileobj = objectFactory.createTransferFileobj();
		fileobj.setSrc("file://verification/testRecord.wav");
		fileobj.setDest("xmsrp://?offerer="+userB+"@"+ipAddressB+";answerer="+myUser+"@"+myIpAddress+";file=testRecord.wav");
		fileobj.setContenttype("audio/x-wav");
		Transfer.Transferobjdone transferobjdone = objectFactory.createTransferTransferobjdone();
		
		transfer.getFileobjOrMessageobj().add(fileobj);
		transfer.setTransferobjdone(transferobjdone);	
		dialogstart.getMomlRequest().add(objectFactory.createTransfer(transfer));				
		msml.getMsmlRequest().add(dialogstart);	

		try		//marshal
		{
			marshaller.marshal(msml, byteArayStream);			
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
		return byteArayStream;		
	}
	public Msml GetInfoAndOkSdp(ByteArrayInputStream sdp)
	{
		Msml msml = objectFactory.createMsml();
		try
		{
			msml = (Msml) unmarshaller.unmarshal(sdp);
		}
		catch(Exception e)
		{
			e.printStackTrace(new PrintWriter(sw)); GUI.println(sw.toString());
		}
		return msml;
	}
	public String GetSessionVersion(Object sdp)
	{		
		String sdpString = new String((byte[])sdp);
		String[] lines = sdpString.split("\n");
		for (String line : lines)
		{		
			if(line.contains("o=")) 
			{				
				String[] subStrings = line.split(" ");				
				return subStrings[2];
			}
		}
		return null;		
	}
}
