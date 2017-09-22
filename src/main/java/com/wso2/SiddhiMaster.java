package com.wso2;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.client.api.async.impl.NMClientAsyncImpl;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ContainerLocalizer;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class SiddhiMaster {

    private static final Logger logger = Logger.getLogger(SiddhiMaster.class);

    private AMRMClientAsync resourceManager;
    private NMClientAsync nmClientAsync;
    private NMCallbackHandler containerListener;
    private Configuration conf;
    private Options options;
    private int numContainers;
    private String conMemory;
    private String appID;
    private List<Thread> launchThreads = new ArrayList<Thread>();
    private List<Container> containers = new ArrayList<Container>();
    private String appMasterHostname = "";
    private int appMasterRpcPort = 0;
    private String appMasterTrackingUrl = "";
    private volatile boolean done = false;
    private JSONArray siddhiApps;
    private JSONObject jsonObject;
    private List<String> nodeIPList = new LinkedList<String>();
    private int defaultPort = 9982;
    private HashMap<String, List<Integer>> stringListHashMap = new HashMap<String, List<Integer>>();
    private JsonReadWrite jsonReadWrite;


    private AtomicInteger allocContainers = new AtomicInteger();
    private AtomicInteger requestedContainers = new AtomicInteger();
    private AtomicInteger failedContainers = new AtomicInteger();
    private AtomicInteger completedContainers = new AtomicInteger();

    private String deploymentJSONURI;
    private String appMasterURI;


    public SiddhiMaster() {

        conf = new YarnConfiguration();

        options = new Options();

        options.addOption("conMemory", true, "Container Memory");
        options.addOption("deploymentJSON", true, "Deployment configuration file path ");
        options.addOption("appID", true, "Application ID");
        options.addOption("appMasterURI", true, "Applicationn Master HDFS URI");


    }

    public static void main(String[] args) {


        SiddhiMaster siddhiMaster = new SiddhiMaster();

        try {

            siddhiMaster.init(args);

            siddhiMaster.run();


        } catch (ParseException e) {

            logger.error("Unexpected Parse error", e);

        } catch (YarnException e) {

            logger.error("Unexpected Yarn Error", e);

        } catch (IOException e) {

            logger.error("Unexpected IOException", e);
        }


    }

    public void init(String[] args) throws ParseException {

        CommandLine cmdLine = new GnuParser().parse(options, args);

        if (!cmdLine.hasOption("conMemory")) {
            throw new IllegalArgumentException("Container Memory not Specified");

        }

        conMemory = cmdLine.getOptionValue("conMemory", "256");   //2048 has to be*/

        appID = cmdLine.getOptionValue("appID");

        Map<String, String> envs = System.getenv();

        String containerIdString = envs.get(ApplicationConstants.Environment.CONTAINER_ID.name());


        String amContainerHome = conf.get("yarn.nodemanager.local-dirs")
                + File.separator + ContainerLocalizer.USERCACHE
                + File.separator
                + System.getenv().get(ApplicationConstants.Environment.USER.toString())
                + File.separator + ContainerLocalizer.APPCACHE
                + File.separator + appID + File.separator
                + containerIdString;


        deploymentJSONURI = amContainerHome + File.separator + cmdLine.getOptionValue("deploymentJSON");

        this.appMasterURI = cmdLine.getOptionValue("appMasterURI");

        jsonReadWrite = new JsonReadWrite();
        this.jsonObject = jsonReadWrite.readConfiguration(deploymentJSONURI);


        if (jsonObject != null) {

            this.siddhiApps = (JSONArray) jsonObject.get("siddhiApps");

            numContainers = 0;

            String s;
            int temp;


            for (int i = 0; i < siddhiApps.size(); i++) {

                s = (String) ((JSONObject) siddhiApps.get(i)).get("parallel");

                temp = Integer.parseInt(s);
                numContainers += temp;
            }

        } else

        {

            throw new IllegalArgumentException("Deployment File error");
        }

    }

    public boolean run() throws IOException, YarnException {
        logger.info("Starting Siddhi Master.....");

        AMRMClientAsync.CallbackHandler allocListener = new RMCallbackHandler();
        resourceManager = AMRMClientAsync.createAMRMClientAsync(1000, allocListener);
        resourceManager.init(conf);
        resourceManager.start();


        containerListener = new NMCallbackHandler();
        nmClientAsync = new NMClientAsyncImpl(containerListener);
        nmClientAsync.init(conf);
        nmClientAsync.start();

        RegisterApplicationMasterResponse applicationMasterResponse = resourceManager.registerApplicationMaster(appMasterHostname, appMasterRpcPort, appMasterTrackingUrl);

        int maxMemoryCluster = applicationMasterResponse.getMaximumResourceCapability().getMemory();

        logger.info("Max memory capability of the cluster: Memory<" + maxMemoryCluster
                + "> ,Max Vcore capablity of the cluster: Vcores<" + applicationMasterResponse.getMaximumResourceCapability().getVirtualCores() + ">"
        );


        if (Integer.parseInt(conMemory) > maxMemoryCluster) {

            //TODO:what if max memory for container is much greater than the memory available
            logger.info("Max memory capability of the cluster  and Assigned mismatch...\nAssigning Max memory capability of the cluster as Memory requirement for the application");
            conMemory = Integer.toString(maxMemoryCluster);

        }

        logger.info("Requesting Containers from Resource Manager");
        for (int i = 0; i < numContainers; ++i) {
            AMRMClient.ContainerRequest containerAsk = setupContainerAskFromRM();
            resourceManager.addContainerRequest(containerAsk);
        }


        requestedContainers.set(numContainers);

        while (!done) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                logger.error("SiddhiMaster Interrupted");
            }
        }

        finish();


        return true;
    }


    private void finish() {
        for (Thread launchThread : launchThreads) {
            try {
                launchThread.join(10000);
            } catch (InterruptedException e) {
                logger.info("Exception thrown in thread join: " + e.getMessage());
                e.printStackTrace();
            }
        }

        logger.info("Application completed. Stopping running containers");
        nmClientAsync.stop();

        logger.info("Application completed. Starting Unregistering SiddhiMaster from ResourceManager");

        try {
            resourceManager.unregisterApplicationMaster(FinalApplicationStatus.SUCCEEDED, "", "");
            logger.info("SiddhiMaster Successfully Unregistered ");

        } catch (YarnException e) {

            logger.error("SiddhiMaster Unregistration failure", e);

        } catch (IOException e) {
            logger.error("SiddhiMaster Unregistration failure", e);
        }

        done = true;

        logger.info("Application completed. Stopping ResourceManager Instance");
        resourceManager.stop();

    }

    private AMRMClient.ContainerRequest setupContainerAskFromRM() {
        //TODO:resource requirements  depend on the user

        Priority pri = Records.newRecord(Priority.class);
        pri.setPriority(0);

        Resource capability = Records.newRecord(Resource.class);
        capability.setMemory(Integer.parseInt(conMemory));
        capability.setVirtualCores(1);

        AMRMClient.ContainerRequest request = new AMRMClient.ContainerRequest(capability, null, null, pri);

        return request;
    }

    private class RMCallbackHandler implements AMRMClientAsync.CallbackHandler {


        public void onContainersCompleted(List<ContainerStatus> statuses) {

            int exitStatus;

            for (ContainerStatus sts : statuses) {

                exitStatus = sts.getExitStatus();

                if (exitStatus == 0) {
                    logger.info("Successfully completed container ID: " + sts.getContainerId());

                } else {
                    if (ContainerExitStatus.ABORTED == exitStatus) {
                        //need to reschedule the container again
                        requestedContainers.decrementAndGet();
                        allocContainers.decrementAndGet();
                        logger.info("Container killed by the framework: " + sts.getContainerId() + "..new  container will be rescheduled");
                    } else {
                        //container being killed due to different reason --->here not allocating them again
                        completedContainers.incrementAndGet();
                        failedContainers.incrementAndGet();

                        //get diagnostic message of failed containers
                        logger.info(sts.getContainerId() + " Container terminated , new container will not be rescheduled:" + "due to " + sts.getDiagnostics());

                    }

                }


            }


            int reschedule = numContainers - requestedContainers.get();

            if (reschedule > 0) {
                for (int i = 0; i < reschedule; ++i) {
                    AMRMClient.ContainerRequest containerAsk = setupContainerAskFromRM();
                    resourceManager.addContainerRequest(containerAsk);
                }


            } else {
                done = true;
            }

        }

        public void onContainersAllocated(List<Container> allocatedContainers) {

            int lastPort;
            List<Integer> portList;
            String nodeIP;

            for (Container allocatedContainer : allocatedContainers)

            {
                containers.add(allocatedContainer);
                nodeIP = allocatedContainer.getNodeHttpAddress().split(":")[0];

                if (stringListHashMap.containsKey(nodeIP)) {
                    portList = stringListHashMap.get(nodeIP);
                    lastPort = portList.get(portList.size() - 1);
                    portList.add(lastPort + 1);
                    stringListHashMap.put(nodeIP, portList);
                } else {
                    portList = new LinkedList<Integer>();
                    portList.add(defaultPort);
                    stringListHashMap.put(nodeIP, portList);
                }

                nodeIPList.add(nodeIP);

            }

            System.out.println("before condition");
            System.out.println(numContainers);
            System.out.println("container liste size" + containers.size());
            if (containers.size() == numContainers) {  //this value depending on the # of containers for the topology



                System.out.println("after condition");

                LaunchContainerRunnable runnableLaunchContainer;


                String name="";
                String app="";
                String tempString;
                String sourceIP;
                String sinkIP;
                String sourcePort;
                int parallel=1;
                int containerNumber = 0;
                int sinkPorts = 1;
                int jsonObectNumber = 0;
                JSONObject jsonSiddhiApp=null;
                JSONObject JSONsinkPort;
                boolean truth=true;
                int testport = 9992;                   //final port only for testing


                for(int i=0;i<containers.size();i++){
                    if (i == containerNumber) {

                        jsonSiddhiApp = (JSONObject) siddhiApps.get(jsonObectNumber);
                        name = (String) jsonSiddhiApp.get("name");
                        parallel = Integer.parseInt((String) jsonSiddhiApp.get("parallel"));

                        //to get number of sinkPorts
                        sinkPorts = 1;

                        if (jsonObectNumber + 1 < siddhiApps.size()) {
                            JSONsinkPort = (JSONObject) siddhiApps.get(jsonObectNumber + 1);
                            sinkPorts = Integer.parseInt((String) JSONsinkPort.get("parallel"));
                        }
                        else
                        {
                            truth=false;
                        }
                        jsonObectNumber++;
                        containerNumber += parallel;

                    }

                    app = (String) jsonSiddhiApp.get("app");


                    sourceIP = nodeIPList.get(i);
                    sourcePort = Integer.toString(stringListHashMap.get(sourceIP).get(0));
                    String temp ;

                    tempString = app.replaceAll("\\$\\{" + name + " source_ip}", sourceIP).replaceAll("\\{" + name + " source_port}", sourcePort);
                    stringListHashMap.get(sourceIP).remove(0);

                    if(truth) {
                        for (int k = 1; k <= sinkPorts; k++) {


                            sinkIP = nodeIPList.get(i + k + parallel - 1);
                            temp = tempString.replaceAll("\\$\\{" + name + " sink_ip}", sinkIP).replaceAll("\\{" + name + " sink_port" + Integer.toString(k ) + "}", Integer.toString(stringListHashMap.get(sinkIP).get(k - 1)));
                            tempString=temp;
                        }
                    }
                    else{
                        //for binding
                        temp = tempString.replaceAll("\\$\\{" + name + " sink_ip}", nodeIPList.get(i)).replaceAll("\\{" + name + " sink_port" + Integer.toString(1 ) + "}", Integer.toString(testport));
                        testport++;

                    }



                    parallel-=1;


                    JSONObject jsonObject1 = new JSONObject();
                    jsonObject1.put("app", tempString);
                    jsonObject1.put("name",name);
                    runnableLaunchContainer = new LaunchContainerRunnable(containers.get(i), containerListener, "SiddhiWorker.tar.gz", "wso2sp-4.0.0-SNAPSHOT", jsonObject1, sourcePort, Integer.toString(i));

                    Thread launchThread = new Thread(runnableLaunchContainer);
                    launchThreads.add(launchThread);
                    launchThread.start();

                }

            }

        }

        public void onShutdownRequest() {

            logger.info("Shutting down SiddhiMaster on ResourceManager request");
            done = true;

        }

        public void onNodesUpdated(List<NodeReport> updatedNodes) {

        }

        public float getProgress() {
            return 0;
        }

        public void onError(Throwable e) {

            logger.error("ResourceManager communication error.Stopping the RM instance:", e);
            resourceManager.stop();

        }


    }


    private class NMCallbackHandler implements NMClientAsync.CallbackHandler {

        public void onContainerStarted(ContainerId containerId, Map<String, ByteBuffer> allServiceResponse) {

        }

        public void onContainerStatusReceived(ContainerId containerId, ContainerStatus containerStatus) {

        }

        public void onContainerStopped(ContainerId containerId) {

        }

        public void onStartContainerError(ContainerId containerId, Throwable t) {

        }

        public void onGetContainerStatusError(ContainerId containerId, Throwable t) {

        }

        public void onStopContainerError(ContainerId containerId, Throwable t) {

        }
    }


    private class LaunchContainerRunnable implements Runnable {

        NMCallbackHandler containerListener;
        Container container;
        String worker;
        String postSiddhiHome;
        JSONObject siddhiApp;
        String sourceport;
        String offset;

        public LaunchContainerRunnable(Container container, NMCallbackHandler containerListener, String worker, String postSiddhiHome, JSONObject siddhiApp, String port, String offset) {
            this.container = container;
            this.containerListener = containerListener;
            this.worker = worker;
            this.postSiddhiHome = postSiddhiHome;
            this.siddhiApp = siddhiApp;
            this.sourceport = port;
            this.offset = offset;


        }

        public void run() {


            String containerId = container.getId().toString();


            ContainerLaunchContext ctx = Records.newRecord(ContainerLaunchContext.class);
            String classpath = "$CLASSPATH:./" + worker;


            Map<String, String> env = new HashMap<String, String>();
            env.put("CLASSPATH", classpath);

            ctx.setEnvironment(env);


            Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();

            String applicationId = container.getId().getApplicationAttemptId().getApplicationId().toString();

            try {
                FileSystem fs = FileSystem.get(conf);

                Path workerDestination = new Path(fs.getHomeDirectory() + File.separator + "wso2sp-4.0.0-SNAPSHOT.tar.gz");
                FileStatus destStatus = fs.getFileStatus(workerDestination);

                LocalResource workerJar = Records.newRecord(LocalResource.class);
                workerJar.setType(LocalResourceType.FILE);
                workerJar.setVisibility(LocalResourceVisibility.APPLICATION);
                workerJar.setResource(ConverterUtils.getYarnUrlFromPath(workerDestination));
                workerJar.setTimestamp(destStatus.getModificationTime());
                workerJar.setSize(destStatus.getLen());


                localResources.put(worker, workerJar);

                //localing main jar file
                Path amJarDestination = new Path(appMasterURI);
                FileStatus amDestStatus = fs.getFileStatus(amJarDestination);
                LocalResource amJar = Records.newRecord(LocalResource.class);
                amJar.setType(LocalResourceType.FILE);
                amJar.setVisibility(LocalResourceVisibility.APPLICATION);
                amJar.setResource(ConverterUtils.getYarnUrlFromPath(amJarDestination));
                amJar.setTimestamp(amDestStatus.getModificationTime());
                amJar.setSize(amDestStatus.getLen());
                localResources.put("SiddhiMaster.jar", amJar);


            } catch (Exception e) {
                e.printStackTrace();
                return;
            }

            ctx.setLocalResources(localResources);

            String containerHome = conf.get("yarn.nodemanager.local-dirs")
                    + File.separator + ContainerLocalizer.USERCACHE
                    + File.separator
                    + System.getenv().get(ApplicationConstants.Environment.USER.toString())
                    + File.separator + ContainerLocalizer.APPCACHE
                    + File.separator + applicationId + File.separator
                    + containerId;


            String siddhiHome = containerHome + File.separator + postSiddhiHome;
            String tempApp = " \\\"" + siddhiApp.get("app") + "\\\" ";

            List<String> commands = new ArrayList<String>();

            commands.add(" 1>>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout" + " 2>>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr ");
            commands.add(" && ");
            commands.add(" tar zxvf " + worker + " -C ./ ");
            commands.add(" && ");


            commands.add(ApplicationConstants.Environment.JAVA_HOME.$() + "/bin/java -cp /usr/local/hadoop/share/hadoop/common/lib/*" + File.pathSeparator + containerHome + "/SiddhiMaster.jar " + "com.wso2.SiddhiConfiguration "
                    + " " + siddhiHome + " " + sourceport + "  " + siddhiApp.get("name") + " " + offset + " " + tempApp + " 1>>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout" +
                    " 2>>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr ");

            ctx.setCommands(commands);

            nmClientAsync.startContainerAsync(container, ctx);


        }

    }


}

