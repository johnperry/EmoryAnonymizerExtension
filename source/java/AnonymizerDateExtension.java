package edu.emory.anonymizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;
import java.util.Locale;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.plugin.AbstractPlugin;
import org.rsna.ctp.stdstages.anonymizer.dicom.AnonymizerExtension;
import org.rsna.ctp.stdstages.anonymizer.dicom.FnCall;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * An AnonymizerExtension to process dates.
 */
public class AnonymizerDateExtension extends AbstractPlugin implements AnonymizerExtension {
	
	static final Logger logger = Logger.getLogger(AnonymizerDateExtension.class);
	static int patientIDTag = DicomObject.getElementTag("PatientID");
	
	static final long oneSecond = 1000;
	static final long oneMinute = 60 * oneSecond;
	static final long oneHour = 60 * oneMinute;
	static final long oneDay = 24 * oneHour;	
	
	String filename;
	File file;
	long lastModified = 0;
	String baseDate;
	long baseTime = 0;
	Hashtable<String,Long> table;
	SimpleDateFormat dcmDateDF = new SimpleDateFormat("yyyyMMdd", Locale.ENGLISH);
	SimpleDateFormat dcmTimeDF = new SimpleDateFormat("kkmmss", Locale.ENGLISH);
	SimpleDateFormat dcmDateTimeDF = new SimpleDateFormat("yyyyMMdd kkmmss", Locale.ENGLISH);
	SimpleDateFormat inDF = new SimpleDateFormat("M/d/yyyy kk:mm:ss", Locale.ENGLISH);

	/**
	 * IMPORTANT: When the constructor is called, neither the
	 * pipelines nor the HttpServer have necessarily been
	 * instantiated. Any actions that depend on those objects
	 * must be deferred until the start method is called.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the plugin.
	 */
	public AnonymizerDateExtension(Element element) {
		super(element);
		baseDate = element.getAttribute("baseDate");
		try { baseTime = dcmDateDF.parse(baseDate).getTime(); }
		catch (Exception ex) { logger.warn("Illegal baseDate: \""+baseDate+"\""); }
		filename = element.getAttribute("dateTableFile");
		file = new File(filename);
		getTable();
		logger.info(getID()+" Plugin instantiated");
	}

	/**
	 * Implement the AnonymizerExtension interface
	 * @param fnCall the specification of the function call.
	 * @return the result of the function call.
	 * @throws Exception
	 */
	public synchronized String call(FnCall fnCall) {
		try{
			getTable();

			//fnCall arguments:
			//[0]: id attribute of the AnonymizerExtension
			//[1]: method name
			//[2]: date element name (only for the relative methods)
			//[3]: time element name (only for the relative methods)

			//Get the method name
			String method = fnCall.getArg(1).trim();

			//Get the PatientID, which is the index into the table
			String patientID = fnCall.context.contents(patientIDTag);

			//Get the injury time
			long injuryTime = table.get(patientID).longValue();
			Date injuryDate = new Date(injuryTime);

			if (method.equals("getInjuryDate")) {
				return dcmDateDF.format(injuryDate);
			}
			else if (method.equals("getInjuryTime")) {
				return dcmTimeDF.format(injuryDate);
			}
			
			String dateString = getElementValue(fnCall, 2);
			String timeString = getElementValue(fnCall, 3);
			long dcmTime = getTime(dateString, timeString);
			long time = (dcmTime - injuryTime) + baseTime;

			if (method.equals("getRelativeDate")) {
				return dcmDateDF.format(time);
			}
			else if (method.equals("getRelativeTime")) {
				return dcmTimeDF.format(time);
			}
			else if (method.equals("getTimeSinceInjury")) {
				long deltaT = dcmTime - injuryTime;
				long days = deltaT / oneDay;
				long rest = deltaT % oneDay;
				long hours = rest / oneHour;
				rest = rest % oneHour;
				long minutes = rest / oneMinute;
				return days + " days; "+hours+" hours; "+minutes+" minutes";				
			}
		}
		catch (Exception returnEmpty) { }
		return "";
	}
	
	private String getElementValue(FnCall fnCall, int arg) {
		String dcmElementName = fnCall.getArg(arg).trim();
		int tag = fnCall.thisTag;
		if (!dcmElementName.equals("this")) {
			tag = DicomObject.getElementTag(dcmElementName);
		}
		return fnCall.context.contents(tag, "").trim();
	}
	
	private long getTime(String date, String time) throws Exception {
		int year = StringUtil.getInt(date.substring(0, 4));
		int month = StringUtil.getInt(date.substring(4, 6));
		int day = StringUtil.getInt(date.substring(6, 8));
		String dateString = month + "/" + day + "/" + year;
		String timeString = "";
		if (time.length() >= 4) timeString = time.substring(0, 2) + ":" + time.substring(2, 4);
		if (time.length() >= 6) timeString += ":" + time.substring(4, 6);
		return inDF.parse(dateString + " " + timeString).getTime();
	}
	
	//Table format:
	//One header line plus many data lines in the form:
	//PatientID,InjuryDate,InjuryTime, [the rest unused]
	//1005,4/14/2010,21:40:00,4/14/2010
	
	private void getTable() {
		//Load the table from the file
		if ((lastModified == 0) || ((file.lastModified() - lastModified) > 5000)) {
			table = new Hashtable<String,Long>();
			BufferedReader br = null;
			try {
				br = new BufferedReader(
							new InputStreamReader(
								new FileInputStream(file),
								FileUtil.utf8));
				String line = br.readLine(); //skip the column headers line
				while ( (line = br.readLine()) != null ) {
					String[] cells = line.split(",");
					String idCell = cells[0].trim();
					String dateCell = cells[1].trim();
					String timeCell = cells[2].trim();
					String dateTime = dateCell + " " + timeCell;
					Date d = inDF.parse(dateTime);
					table.put(idCell, new Long(d.getTime()));
				}
			}
			catch (Exception ex) { }
			finally { FileUtil.close(br); }
		}
	}
}