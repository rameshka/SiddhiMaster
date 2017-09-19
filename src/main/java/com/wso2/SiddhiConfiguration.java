package com.wso2;


import org.apache.log4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class SiddhiConfiguration {

    private String appString;
    private String siddhiHomePath;
    private String appName;
    private String sourcePort;
    private String offset;
    private Logger logger = Logger.getLogger(SiddhiConfiguration.class);


    public static void main(String[] args) {
        //start worker demons


        SiddhiConfiguration siddhiConfiguration = new SiddhiConfiguration();

        siddhiConfiguration.init(args);

        siddhiConfiguration.createSiddhiApp();



        siddhiConfiguration.editYamlConfiguration();

        siddhiConfiguration.startSidhiWorker();


    }

    public void init(String[] args) {

        this.siddhiHomePath = args[0];
        this.sourcePort = args[1];
        this.appName = args[2];
        this.offset=args[3];
        this.appString = "@App:name(\""+appName+"\")\n";


            appString = appString + " " + args[4];

        System.out.println(args.length);

    }


    public void createSiddhiApp() {


        String siddhiFilePath = siddhiHomePath + File.separator + "deployment" + File.separator + "siddhi-files" + File.separator + appName + ".siddhi";

        BufferedWriter bw = null;
        try {

            File file = new File(siddhiFilePath);

            if (!file.exists()) {
                file.createNewFile();
            }

            FileWriter fw = new FileWriter(file);
            bw = new BufferedWriter(fw);
            bw.write(appString);


        } catch (IOException e) {

            logger.info(e.getMessage());

        } finally {

            if (bw != null) {
                try {
                    bw.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
    }


    public void startSidhiWorker() {


        String siddhiRun = String.format("%s%sbin%sworker.sh", siddhiHomePath, File.separator, File.separator);
        ProcessBuilder siddhiProcess = new ProcessBuilder(siddhiRun);

        try {

            Process process = siddhiProcess.start();


            BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));


            BufferedWriter bw = new BufferedWriter(new FileWriter(new File(siddhiHomePath + File.separator + "log.txt")));
            String line;
            while ((line = br.readLine()) != null) {
                bw.write(line);
            }

        } catch (IOException e) {

            logger.info(e.getMessage());
            System.out.println("logging from siddhiConfiguration File");
        }


    }


    public void editYamlConfiguration() {


        String yamlPath = String.format("%s%sconf%sdeployment.yaml", siddhiHomePath, File.separator, File.separator);


        try {
            FileInputStream fis = new FileInputStream(new File(yamlPath));
            final DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Yaml yaml = new Yaml(options);


            Map<String, Object> loaded = (Map<String, Object>) yaml.load(fis);

            ((Map) ((Map) loaded.get("wso2.carbon")).get("ports")).put("offset", offset);

            Map<String, Object> data4 = new HashMap<String, Object>();
            data4.put("port", sourcePort);
            Map<String, Object> data3 = new HashMap<String, Object>();
            data3.put("name", "tcp");
            data3.put("namespace", "source");
            data3.put("properties", data4);
            Map<String, Object> data2 = new HashMap<String, Object>();
            data2.put("extension", data3);
            List<Object> extensionList = new ArrayList<Object>();
            extensionList.add(data2);
            Map<String, Object> data1 = new HashMap<String, Object>();
            data1.put("extensions",extensionList);
            loaded.put("siddhi", data1);
            yaml = new Yaml(options);
            Writer writer = new FileWriter(yamlPath);
            yaml.dump(loaded, writer);
            writer.close();


        } catch (FileNotFoundException ex) {

            logger.error("Deployment Configuration File not found",ex);

        } catch (IOException e) {
            logger.error("Unexpected Error",e);

        }

    }
}