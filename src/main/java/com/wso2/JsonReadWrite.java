package com.wso2;

import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;


public class JsonReadWrite {

    private static final Logger logger = Logger.getLogger(SiddhiMaster.class);


    public JSONObject readConfiguration(String deploymentJSONURI) {
        JSONParser parser = new JSONParser();
        Object obj;
        JSONObject jsonObject = null;


        try {
            obj = parser.parse(new FileReader(deploymentJSONURI));


            jsonObject = (JSONObject) obj;


        } catch (IOException e) {

            logger.error("Unexpected IO error", e);

        } catch (org.json.simple.parser.ParseException e) {
            logger.error("Unexpexted Simple parser parseException", e);
        }


        return jsonObject;
    }

    public void writeConfiguration(JSONObject jsonObject, String deploymentJSONURI) throws IOException {

        File file = new File(deploymentJSONURI);
        if (file.exists())
        {
            file.delete();
        }

        file.createNewFile();

        String removeEscape = jsonObject.toJSONString().replaceAll("\\\\","");

        FileWriter fileWriter = new FileWriter(deploymentJSONURI);
        fileWriter.write(removeEscape);

        fileWriter.flush();

    }


}