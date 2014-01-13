/*
 *
 * CODENVY CONFIDENTIAL
 * ________________
 *
 * [2012] - [2013] Codenvy, S.A.
 * All Rights Reserved.
 * NOTICE: All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any. The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */


package com.codenvy.analytics.services;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codenvy.analytics.Configurator;
import com.codenvy.analytics.Utils;
import com.codenvy.analytics.datamodel.ListValueData;
import com.codenvy.analytics.datamodel.LongValueData;
import com.codenvy.analytics.datamodel.MapValueData;
import com.codenvy.analytics.datamodel.SetValueData;
import com.codenvy.analytics.datamodel.ValueData;
import com.codenvy.analytics.metrics.AbstractUsersProfile;
import com.codenvy.analytics.metrics.Metric;
import com.codenvy.analytics.metrics.MetricFactory;
import com.codenvy.analytics.metrics.MetricType;
import com.codenvy.analytics.metrics.Parameters;
import com.codenvy.analytics.metrics.UsersStatisticsList;

/** @author <a href="mailto:abazko@codenvy.com">Anatoliy Bazko</a> */
public class ActOn extends Feature {
    public static final String FILE_NAME = "ideuserupdate.csv";

    private static final String AVAILABLE = "acton.available";
    
    private static final String PAGE_SIZE_PARAMETER = "acton.page.size";

    private static final String MAIL_TEXT    = "acton.mail.text";
    private static final String MAIL_SUBJECT = "acton.mail.subject";
    private static final String MAIL_TO      = "acton.mail.to";

    private static final String FTP_PASSWORD    = "acton.ftp.password";
    private static final String FTP_LOGIN       = "acton.ftp.login";
    private static final String FTP_SERVER      = "acton.ftp.server";
    private static final String FTP_PORT        = "acton.ftp.port";
    private static final String FTP_TIMEOUT     = "acton.ftp.timeout";
    private static final String FTP_MAX_EFFORTS = "acton.ftp.maxEfforts";
    private static final String FTP_AUTH        = "acton.ftp.auth";

    private static final int PAGE_SIZE; 
    
    private static final Logger LOG = LoggerFactory.getLogger(ActOn.class);

    static {
        if (Configurator.exists(PAGE_SIZE_PARAMETER)) {
            PAGE_SIZE = Configurator.getInt(PAGE_SIZE_PARAMETER);
        } else {
            PAGE_SIZE = 1000;
        }
    }
    
    @Override
    public boolean isAvailable() {
        return Configurator.getBoolean(AVAILABLE);
    }

    @Override
    protected Map<String, String> initializeDefaultContext() throws ParseException {
        return Utils.initializeContext(Parameters.TimeUnit.LIFETIME);
    }

    @Override
    protected void putParametersInContext(Map<String, String> context) {
    }

    @Override
    protected void doExecute(Map<String, String> context) throws IOException, ParseException {
        LOG.info("ActOn is started");
        long start = System.currentTimeMillis();

        try {
            File file = prepareFile(context);

            transferToFtp(file);
            sendNotificationMail();
        } finally {
            LOG.info("ActOn is finished in " + (System.currentTimeMillis() - start) / 1000 + " sec.");
        }
    }

    protected void sendNotificationMail() throws IOException {
        MailService.Builder builder = new MailService.Builder();
        builder.setSubject(Configurator.getString(MAIL_SUBJECT));
        builder.setText(Configurator.getString(MAIL_TEXT));
        builder.setTo(Configurator.getString(MAIL_TO));
        MailService mailService = builder.build();

        mailService.send();
    }

    /** Sends file directly to FTP server. */
    private void transferToFtp(File file) throws IOException {
        for (int i = 0; i < Configurator.getInt(FTP_MAX_EFFORTS); i++) {
            FTPSClient ftp = new FTPSClient(Configurator.getString(FTP_AUTH), false);

            try {
                doOpenConnection(ftp);
                doTransfer(file, ftp);
                doCloseConnection(ftp);

                break; // file transferred successfully

            } catch (SocketTimeoutException e) {
                LOG.error(e.getMessage());

            } catch (IOException e) {
                if (ftp.isConnected()) {
                    ftp.disconnect();
                }

                throw e;
            }
        }
    }

    private void doCloseConnection(FTPSClient ftp) throws IOException {
        ftp.logout();
        ftp.disconnect();
    }

    private void doOpenConnection(FTPSClient ftp) throws IOException {
        ftp.setDefaultTimeout(Configurator.getInt(FTP_TIMEOUT));
        ftp.connect(Configurator.getString(FTP_SERVER), Configurator.getInt(FTP_PORT));

        ftp.setSendBufferSize(65536);
        ftp.setBufferSize(65536);

        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            throw new IOException("FTP connection failed");
        }

        if (!ftp.login(Configurator.getString(FTP_LOGIN), Configurator.getString(FTP_PASSWORD))) {
            ftp.logout();
            throw new IOException("FTP login failed");
        }

        ftp.enterLocalPassiveMode();
        ftp.execPBSZ(0);
        ftp.execPROT("P");
        ftp.setFileType(FTPSClient.ASCII_FILE_TYPE);
    }

    private void doTransfer(File file, FTPSClient ftp) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            if (!ftp.storeFile(file.getName(), in)) {
                throw new IOException("File " + file.getName() + " was not transferred to the server");
            }
        }
    } 
    
    protected File prepareFile(Map<String, String> context) throws IOException, ParseException {
        File file = new File(Configurator.getTmpDir(), FILE_NAME);

        int currentPage = 1;        
        Parameters.PER_PAGE.put(context, "" + PAGE_SIZE);        

        Map<ValueData, Map<String, ValueData>> usersProfiles = getUsersProfiles();
        
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            writeHeader(out);
            
            while(true) {
                Parameters.PAGE.put(context, "" + currentPage++);
                List<ValueData> usersStatistics = getUsersStatistics(context);
                        
                Set<ValueData> activeUsers = getActiveUsersLastMonth(context);
        
                writeUsersWithStatistics(activeUsers, usersStatistics, usersProfiles, out);
    
                if (usersStatistics.size() < PAGE_SIZE) {
                    break;
                }
            }
            
            writeUsersWithoutStatistics(usersProfiles, out);
        }
        
        return file;
    }  
    
    
    private void writeUsersWithoutStatistics(Map<ValueData, Map<String, ValueData>> usersProfiles,
                                             BufferedWriter out) throws IOException {

        for (Map.Entry<ValueData, Map<String, ValueData>> entry : usersProfiles.entrySet()) {
            writeStatistics(out,
                            Collections.<String, ValueData>emptyMap(),
                            entry.getValue(),
                            false);
        }
    }

    private void writeUsersWithStatistics(Set<ValueData> activeUsers,
                                          List<ValueData> usersStatistics,
                                          Map<ValueData, Map<String, ValueData>> usersProfiles,
                                          BufferedWriter out) throws IOException {

        for (ValueData object : usersStatistics) {
            Map<String, ValueData> stat = ((MapValueData)object).getAll();
            ValueData userEmail = stat.get(UsersStatisticsList.USER);

            Map<String, ValueData> profile = usersProfiles.remove(userEmail);
            boolean isActive = activeUsers.contains(userEmail);

            writeStatistics(out,
                            stat,
                            profile == null ? Collections.<String, ValueData>emptyMap() : profile,
                            isActive);
        }
    }

    private Set<ValueData> getActiveUsersLastMonth(Map<String, String> context) throws ParseException, IOException {
        context = Utils.clone(context);

        Calendar calendar = Utils.getToDate(context);
        calendar.add(Calendar.DAY_OF_MONTH, -29);
        Utils.putFromDate(context, calendar);

        Metric activeUsersList = MetricFactory.getMetric(MetricType.ACTIVE_USERS_SET);
        SetValueData valueData = (SetValueData)activeUsersList.getValue(context);

        return valueData.getAll();
    }

    private List<ValueData> getUsersStatistics(Map<String, String> context) throws IOException, ParseException {
        Metric usersStatistics = MetricFactory.getMetric(MetricType.USERS_STATISTICS_LIST);
        ListValueData valueData = (ListValueData)usersStatistics.getValue(context);

        return valueData.getAll();
    }

    private Map<ValueData, Map<String, ValueData>> getUsersProfiles() throws IOException, ParseException {
        Map<String, String> context = Utils.initializeContext(Parameters.TimeUnit.LIFETIME);

        Metric usersProfiles = MetricFactory.getMetric(MetricType.USERS_PROFILES_LIST);
        ListValueData valueData = (ListValueData)usersProfiles.getValue(context);

        Map<ValueData, Map<String, ValueData>> result = new HashMap<>(valueData.size());

        for (ValueData object : valueData.getAll()) {
            Map<String, ValueData> profile = ((MapValueData)object).getAll();
            result.put(profile.get(AbstractUsersProfile.USER_EMAIL), profile);
        }

        return result;
    }


    private void writeStatistics(BufferedWriter out,
                                 Map<String, ValueData> stat,
                                 Map<String, ValueData> profile,
                                 boolean isActive) throws IOException {

        writeString(out, profile.get(AbstractUsersProfile.USER_EMAIL));
        out.write(",");

        writeString(out, profile.get(AbstractUsersProfile.USER_FIRST_NAME));
        out.write(",");

        writeString(out, profile.get(AbstractUsersProfile.USER_LAST_NAME));
        out.write(",");

        writeString(out, profile.get(AbstractUsersProfile.USER_PHONE));
        out.write(",");

        writeString(out, profile.get(AbstractUsersProfile.USER_COMPANY));
        out.write(",");

        writeInt(out, stat.get(UsersStatisticsList.PROJECTS));
        out.write(",");

        writeInt(out, stat.get(UsersStatisticsList.BUILDS));
        out.write(",");

        writeInt(out, stat.get(UsersStatisticsList.DEPLOYS));
        out.write(",");

        LongValueData time = (LongValueData)stat.get(UsersStatisticsList.TIME);
        if (time == null) {
            writeNotNullStr(out, "0");
        } else {
            writeNotNullStr(out, "" + (time.getAsLong() / 60));
        }
        out.write(",");

        writeNotNullStr(out, Boolean.toString(isActive));
        out.newLine();
    }

    /** Write string value accordingly to CSV specification. */
    private void writeString(BufferedWriter out, ValueData valueData) throws IOException {
        if (valueData == null) {
            writeNotNullStr(out, "");
        } else {
            writeNotNullStr(out, valueData.getAsString());
        }
    }

    private void writeInt(BufferedWriter out, ValueData valueData) throws IOException {
        if (valueData == null) {
            writeNotNullStr(out, "0");
        } else {
            writeNotNullStr(out, valueData.getAsString());
        }
    }

    private void writeNotNullStr(BufferedWriter out, String str) throws IOException {
        out.write("\"");
        out.write(str.replace("\"", "\"\"")); // quoting
        out.write("\"");
    }

    private void writeHeader(BufferedWriter out) throws IOException {
        out.write("email,firstName,lastName,phone,company,projects,builts,deployments,spentTime,inactive");
        out.newLine();
    }
}
