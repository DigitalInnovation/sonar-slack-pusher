package org.jenkinsci.plugins.sonarslackpusher;

public class Attachment {

   private String alert;
   private String color;
   private String alertText;

private String newMajorViolation;

private String newCriticalViolation;

private String newMinorViolation;
 
public String getAttachment() {
	   
	   String message = "{\"text\":\"*" + alert
         + "*\\n*Reason:*\\n" + alertText ;
         if(null != newCriticalViolation || null != newMajorViolation  || null != newMinorViolation) {
        	message = message + "*\\n*Violations:*" ;
        	if(null != newCriticalViolation) {
        	 message = message + "\\n" + newCriticalViolation ;
        	}
        	if (null != newMajorViolation) {
        		message = message + "\\n" +newMajorViolation;
        	}
        	if (null != newMinorViolation) {
        		message = message +  "\\n" + newMinorViolation;
        	}
         }
        
         message = message + "\",\"color\":\"" + color;
         message = message + "\",\"mrkdwn_in\": [\"text\"]}";
         
         return message;
   }

   public void setAlert(String alert) {
      this.alert = alert.equalsIgnoreCase("ERROR") ? "RED" : "GREEN";
      this.color = alert.equalsIgnoreCase("ERROR") ? "danger" : "warning";
   }

   public void setAlertText(String alertText) {
      String alerts = alertText.replaceAll(",", "\\\\n-");
      this.alertText = "- " + alerts;
   }
   public void setMajorViolation(String newMajorViolation) {
	     this.newMajorViolation = newMajorViolation;
	}
   public void setMinorViolation(String newMinorViolation) {
	     this.newMinorViolation = newMinorViolation;
	}
   public void setCriticalViolation(String newCriticalViolation) {
	     this.newCriticalViolation = newCriticalViolation;
	}
   
   public String getNewMinorViolation() {
	return newMinorViolation;
}
   public String getNewMajorViolation() {
	return newMajorViolation;
}
   public String getNewCriticalViolation() {
	return newCriticalViolation;
}
   public String getAlertText() {
	return alertText;
}
}
