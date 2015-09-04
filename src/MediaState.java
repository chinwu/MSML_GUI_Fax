
/*
 * define the state of a call during media.
 * modify this enum when call flow changes.
 * 
 * this simple sample starts greeting, detect dtmf 1 for recording, dtmf 2 for playback recording.
 * when done with recording or playback, go back to greeting.
 */
public enum MediaState 
{
	IDLE(""),
	TERMINATE(""),

	GREETING(GUI.txtMomlLocation.getText() + GUI.txtGreeting.getText()),	
	RECORDING(GUI.txtMomlLocation.getText() + GUI.txtRecord.getText());
	
	public String scriptSource;
	public MediaState nextState;
	private MediaState(String src)	{	scriptSource = src;	}	
	
	public String GetScriptSource()
	{
		return scriptSource;
	}
	
}
