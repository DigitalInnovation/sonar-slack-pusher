package org.jenkinsci.plugins.sonarslackpusher;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;

/**
 * Notifies a configured Slack channel of Sonar quality gate checks
 * through the Sonar API.
 */
public class SonarSlackPusher extends Notifier {

   private String hook;
   private String sonarUrl;
   private String jobName;
   private String branchName;
   private String resolvedBranchName;
   private String additionalChannel;
   private String username;
   private String password;

   private PrintStream logger = null;

   // Notification contents
   private String branch = null;
   private String id;
   private Attachment attachment = null;

   @DataBoundConstructor
   public SonarSlackPusher(String hook, String sonarUrl, String jobName, String branchName, String additionalChannel, String username, String password) {
      this.hook = hook.trim();
      String url = sonarUrl.trim();
      this.sonarUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
      this.jobName = jobName.trim();
      this.branchName = branchName.trim();
      this.additionalChannel = additionalChannel.trim();
      this.username = username;
      this.password = password;
   }

   public String getHook() {
      return hook;
   }

   public String getSonarUrl() {
      return sonarUrl;
   }

   public String getJobName() {
      return jobName;
   }

   public String getBranchName() {
      return branchName;
   }

   public String getAdditionalChannel() {
      return additionalChannel;
   }

   public String getUsername() {
      return username;
   }

   public String getPassword() {
      return password;
   }

   @Override
   public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
      // Clean up
      attachment = null;
      logger = listener.getLogger();
      resolvedBranchName = parameterReplacement(branchName, build, listener);
      try {
         getAllNotifications(getSonarData());
      } catch (Exception e) {
         return false;
      }
      pushNotification();
      return true;
   }

   private String parameterReplacement(String str, AbstractBuild<?, ?> build, BuildListener listener) {
      try {
         EnvVars env = build.getEnvironment(listener);
         env.overrideAll(build.getBuildVariables());
         ArrayList<String> params = getParams(str);
         for (String param : params) {
            if (env.containsKey(param)) {
               str = env.get(param);
            } else if (build.getBuildVariables().containsKey(param)) {
               str = build.getBuildVariables().get(param);
            } else {
               str = null;
            }
         }
      } catch (InterruptedException ie) {
      } catch (IOException ioe) {
      } finally {
         return str;
      }
   }

   private ArrayList<String> getParams(String str) {
      ArrayList<String> params = new ArrayList<String>();
      final String start = java.util.regex.Pattern.quote("${");
      final String end = "}";

      String[] rawParams = str.split(start);
      for (int i = 1; i < rawParams.length; i++) {
         if (rawParams[i].contains(end)) {
            String[] raw = rawParams[i].split(java.util.regex.Pattern.quote(end));
            if (raw.length > 0) {
               params.add(raw[0]);
            }
         }
      }
      return params;
   }

   @Extension
   public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

      public DescriptorImpl() {
         load();
      }

      @Override
      public String getDisplayName() {
         return "Sonar Slack pusher";
      }

      @Override
      public boolean isApplicable(Class<? extends AbstractProject> jobType) {
         return true;
      }

      public FormValidation doCheckHook(@QueryParameter String value)
         throws IOException, ServletException {
         String url = value;
         if ((url == null) || url.equals("")) {
            return FormValidation.error("Please specify a valid URL");
         } else {
            try {
               new URL(url);
               return FormValidation.ok();
            } catch (Exception e) {
               return FormValidation.error("Please specify a valid URL.");
            }
         }
      }

      public FormValidation doCheckSonarUrl(@QueryParameter String value)
         throws IOException, ServletException {
         String url = value;
         if ((url == null) || url.equals("")) {
            return FormValidation.error("Please specify a valid URL");
         } else {
            try {
               new URL(url);
               return FormValidation.ok();
            } catch (Exception e) {
               return FormValidation.error("Please specify a valid URL.");
            }
         }
      }

      public FormValidation doCheckJobName(@QueryParameter String value)
         throws IOException, ServletException {
         String name = value;
         if ((name == null) || name.equals("")) {
            return FormValidation.error("Please enter a Sonar job name.");
         }
         return FormValidation.ok();
      }
   }

   public BuildStepMonitor getRequiredMonitorService() {
      return BuildStepMonitor.NONE;
   }

   private String getSonarData() throws Exception {
      String path = "/api/resources?metrics=alert_status,quality_gate_details,new_major_violations,new_critical_violations,new_minor_violations&includealerts=true&includetrends=true";
      CloseableHttpClient client = HttpClientBuilder.create().build();
      HttpGet get = new HttpGet(sonarUrl + path);

      if (username != null || !username.isEmpty()) {
         String encoding = new Base64().encodeAsString(new String(username + ":" + password).getBytes());
         get.setHeader("Authorization", "Basic " + encoding);
      }

      CloseableHttpResponse res;
      try {
         logger.println("[ssp] Calling SonarQube on: " + sonarUrl + path);
         res = client.execute(get);
         if (res.getStatusLine().getStatusCode() != 200) {
            logger.println("[ssp] Got a non 200 response from SonarQube. Server responded with '"+res.getStatusLine().getStatusCode()+" : "+res.getStatusLine
               ().getReasonPhrase()+"'");
            throw new Exception();
         }
         return EntityUtils.toString(res.getEntity());
      } catch (Exception e) {
         logger.println("[ssp] Could not get Sonar results, exception: '" + e.getMessage() + "'");
         throw e;
      } finally {
         client.close();
      }
   }

   private void getAllNotifications(String data) {
      JSONParser jsonParser = new JSONParser();
      JSONArray jobs = null;
      attachment = new Attachment();
      try {
         jobs = (JSONArray) jsonParser.parse(data);
      } catch (ParseException pe) {
         logger.println("[ssp] Could not parse the response from Sonar '" + data + "'");
      }
      String name = jobName;
      if (resolvedBranchName != null && !resolvedBranchName.equals("")) {
         name += " " + resolvedBranchName;
         name.trim();
      }

      for (Object job : jobs) {
         if (((JSONObject) job).get("name").toString().equals(name)) {
            id = ((JSONObject) job).get("id").toString();
            if (((JSONObject) job).get("branch") != null) {
               branch = ((JSONObject) job).get("branch").toString();
            }
            JSONArray msrs = (JSONArray) ((JSONObject) job).get("msr");
            for (Object msr : msrs) {
               if (((JSONObject) msr).get("key").equals("alert_status")) {
                  if (((JSONObject) msr).get("alert") != null) {
                     String alert = ((JSONObject) msr).get("alert").toString();
                     if (alert.equalsIgnoreCase("ERROR") || alert.equalsIgnoreCase("WARN")) {
                        attachment.setAlert(alert);
                        attachment.setAlertText(((JSONObject) msr).get("alert_text").toString());
                     }
                  }
               }
               if (((JSONObject) msr).get("key").equals("new_major_violations") && !(((JSONObject) msr).get("fvar1").toString()).equals("0")) {
                   String majorText = ((JSONObject) msr).get("fvar1").toString();
                      attachment.setMajorViolation("Major Violation added:"+majorText);
                }
               if (((JSONObject) msr).get("key").equals("new_minor_violations") && !(((JSONObject) msr).get("fvar1").toString()).equals("0")) {
                   String minorText = ((JSONObject) msr).get("fvar1").toString();
                      attachment.setMinorViolation("Minor Violation added:"+minorText);
                }
               if (((JSONObject) msr).get("key").equals("new_critical_violations") && !(((JSONObject) msr).get("fvar1").toString()).equals("0")) {
                   String criticalText = ((JSONObject) msr).get("fvar1").toString();
                      attachment.setCriticalViolation("Critical Violation added:"+criticalText);
                }
            }
         }
      }
   }

   private void pushNotification() {
      if (null == attachment.getAlertText() && null == attachment.getNewCriticalViolation() && 
    		  null == attachment.getNewMajorViolation() && null == attachment.getNewMinorViolation()) {
         String msg = "[ssp] No failed quality checks found for project '" + jobName;

         if (resolvedBranchName != null) {
            msg += " " + resolvedBranchName;
         }
         msg += "' nothing to report to the Slack channel.";
         logger.println(msg);
         attachment.setAlert("GREEN");
         attachment.setAlertText("No Issues found");
         //return;
      } else if (null == attachment.getAlertText()) {
    	  attachment.setAlert("ERROR");
          attachment.setAlertText("Issues found");
      }
      String linkUrl = null;
      try {
         linkUrl = new URI(sonarUrl + "/dashboard/index/" + id).normalize().toString();
      } catch (URISyntaxException use) {
         logger.println("[ssp] Could not create link to Sonar job with the following content'" + sonarUrl + "/dashboard/index/" + id + "'");
      }
      String message = "{";
      if (additionalChannel != null) {
         message += "\"channel\":\"" + additionalChannel + "\",";
      }
      message += "\"username\":\"Sonar Slack Pusher\",";
      message += "\"text\":\"<" + linkUrl + "|*Sonar job*>\\n" +
         "*Job:* " + jobName;
      if (branch != null) {
         message += "\\n*Branch:* " + branch;
      }
      message += "\",\"attachments\":[";
      message += attachment.getAttachment();
      message += "]}";
      HttpPost post = new HttpPost(hook);
      HttpEntity entity = new StringEntity(message, "UTF-8");
      post.addHeader("Content-Type", "application/json");
      post.setEntity(entity);
      HttpClient client = HttpClientBuilder.create().build();
      logger.println("[ssp] Pushing notification(s) to the Slack channel.");
      try {
         HttpResponse res = client.execute(post);
         if (res.getStatusLine().getStatusCode() != 200) {
            logger.println("[ssp] Could not push to Slack... got a non 200 response. Post body: '" + message + "'");
         }
      } catch (IOException ioe) {
         logger.println("[ssp] Could not push to slack... got an exception: '" + ioe.getMessage() + "'");
      }
   }
}
