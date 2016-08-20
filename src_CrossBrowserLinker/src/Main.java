import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.UUID;

public class Main {
	private static final Integer LINKAGE_THRESHOLD_MILLISECONDS = 1000 * 60 * 3; //3 minutes.
	
	/**
	 * The aim of this is to go through the Samples database and link SampleSetIDs of samples from different browsers on the same computer.
	 * To do this we look for samples with the same IP that appear within LINKAGE_THRESHOLD_SECONDS of each other and consider them to be from the same computer.
	 * Linkages are then stored in the database table ....
	 */	
	public static void main(String args[]) throws SQLException, ParseException{
		Connection conn = null;
		Properties connectionProps = new Properties();
		connectionProps.put("user", "root");

		conn = DriverManager.getConnection("jdbc:mysql://localhost/browserprint", connectionProps);
		
		PreparedStatement getSampleTimes = conn.prepareStatement("SELECT `Samples`.`IP`, `SampleSets`.`SampleSetID`, `Samples`.`Platform`, `Samples`.`TimeStamp`"
				+ " FROM `Samples` INNER JOIN `SampleSets` ON `Samples`.`SampleID` = `SampleSets`.`SampleID`"
				+ " WHERE `Samples`.`CookiesEnabled` = TRUE AND `Samples`.`Platform` IS NOT null"
				+ " ORDER BY `Samples`.`IP`, `Samples`.`Platform`, `Samples`.`TimeStamp`;");
		
		System.out.println("-----START-----");
		
		//https://stackoverflow.com/questions/3389348/parse-any-date-in-java
		
		HashMap<String, LinkedHashSet<String>> graph = new HashMap<String, LinkedHashSet<String>>();
		ResultSet rs = getSampleTimes.executeQuery();
		rs.next();
		RsObject tmp1 = new RsObject(rs);
		while(rs.next()){
			RsObject tmp2 = new RsObject(rs);
			
			if(tmp1.ip.equals(tmp2.ip) && !tmp1.sampleSetID.equals(tmp2.sampleSetID) && tmp1.platform.equals(tmp2.platform)){
				if(tmp2.dateTime.getTime() - tmp1.dateTime.getTime() < LINKAGE_THRESHOLD_MILLISECONDS){
					//System.out.println("Linked " + tmp1.sampleSetID + " to " + tmp2.sampleSetID);
					
					//See if either of these samples are part of a SampleSuperSet yet, if so get their UUID.
					addConnection(graph, tmp1.sampleSetID, tmp2.sampleSetID);
					addConnection(graph, tmp2.sampleSetID, tmp1.sampleSetID);
				}
			}
			tmp1 = tmp2;
		}
		
		//Traverse the connections assigning labels as you go
		PreparedStatement checkSuperSampleSetExists = conn.prepareStatement("SELECT 1 FROM `SuperSampleSets` WHERE `SampleSuperSetID` = ? LIMIT 1");
		PreparedStatement checkSampleSetHasLabel = conn.prepareStatement("SELECT 1 FROM `SuperSampleSets` WHERE `SampleSetID` = ? LIMIT 1");
		PreparedStatement insertSuperSampleSet = conn.prepareStatement("INSERT INTO `SuperSampleSets`(`SampleSuperSetID`, `SampleSetID`) VALUES(?, ?);");
		for(String sampleSetID: graph.keySet()){
			//Get a fresh, unused sampleSuperSetID
			String sampleSuperSetID = getUnusedSampleSuperSetID(checkSuperSampleSetExists);
			//Traverse the graph
			f(graph, sampleSuperSetID, checkSampleSetHasLabel, sampleSetID, insertSuperSampleSet);
		}
		
		System.out.println("------END------");
	}
	
	public static void f(HashMap<String, LinkedHashSet<String>> graph, String sampleSuperSetID, PreparedStatement checkSampleSetHasLabel, String sampleSetID, PreparedStatement insertSuperSampleSet) throws SQLException{
		//Check whether this sampleSet has a label yet.
		{
			checkSampleSetHasLabel.setString(1, sampleSetID);
			ResultSet rs = checkSampleSetHasLabel.executeQuery();
			checkSampleSetHasLabel.clearParameters();
			boolean sampleSetHasLabel = rs.next();
			rs.close();
			if(sampleSetHasLabel){
				//We've seen this before
				return;
			}
		}
		
		//Store label in database
		insertSuperSampleSet.setString(1, sampleSuperSetID);
		insertSuperSampleSet.setString(2, sampleSetID);
		insertSuperSampleSet.executeUpdate();
		insertSuperSampleSet.clearParameters();
		
		Iterator<String> it = graph.get(sampleSetID).iterator();
		while(it.hasNext()){
			f(graph, sampleSuperSetID, checkSampleSetHasLabel, it.next(), insertSuperSampleSet);
		}
	}

	public static String getUnusedSampleSuperSetID(PreparedStatement checkSuperSampleSetExists) throws SQLException{
		String sampleSuperSetID;
		for(;;){
			sampleSuperSetID = UUID.randomUUID().toString();
			checkSuperSampleSetExists.clearParameters();
			checkSuperSampleSetExists.setString(1, sampleSuperSetID);
			ResultSet rs = checkSuperSampleSetExists.executeQuery();
			if(rs.next() == false){
				//UUID is unique
				break;
			}
		}
		return sampleSuperSetID;
	}
	
	public static void addConnection(HashMap<String, LinkedHashSet<String>> graph, String sampleSetID1, String sampleSetID2){
		LinkedHashSet<String> neighbors;
		if((neighbors = graph.get(sampleSetID1)) == null){
			neighbors = new LinkedHashSet<String>();
			graph.put(sampleSetID1, neighbors);
		}
		neighbors.add(sampleSetID2);
	}
}

class RsObject{
	public String ip;
	public String sampleSetID;
	public String platform;
	public Timestamp dateTime;
	
	public RsObject(ResultSet rs) throws SQLException {
		this.ip = rs.getString(1);
		this.sampleSetID = rs.getString(2);
		this.platform = rs.getString(3);
		this.dateTime = rs.getTimestamp(4);
	}
}