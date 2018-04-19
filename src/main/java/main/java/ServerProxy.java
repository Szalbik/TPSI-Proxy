package main.java;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.Integer.parseInt;
import static java.nio.file.Files.newBufferedReader;

public class ServerProxy {
    public static void main(String[] args) throws Exception {
        int port = 8888;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new RootHandler());
        System.out.println("Starting server on port: " + port);
        server.start();
    }

    static class RootHandler implements HttpHandler {

        public void handle(HttpExchange exchange) throws IOException {
            try {
                List<String> blackList = new ArrayList<String>();
                try (BufferedReader br = new BufferedReader(new FileReader("/Users/Szalbik/Downloads/ServerProxy/blacklist.txt"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // process the line.
                        blackList.add(line);
                    }
                }

    //            Ustawienie adresu
                URL addres = exchange.getRequestURI().toURL();
    //            System.out.println(addres);
                System.out.println("Address: " + addres);

                for (String item:blackList) {
                    if (addres.toString().contains(item)) {
                        exchange.sendResponseHeaders(403, -1);
                    }
                }

                Map<String, List<Integer>> stats = new HashMap<String, List<Integer>>(); // Zmienna (globalna) dla statystyk

//              Czytanie z pliku CSV
                CSVReader csvReader = new CSVReader(new FileReader("/Users/Szalbik/Downloads/ServerProxy/statystics.csv"));
                List<String[]> records = csvReader.readAll();
                odczytZPliku(stats, records);
                csvReader.close();

//            Connection setup
                HttpURLConnection conn = (HttpURLConnection) addres.openConnection();

//            Przepisanie RequestHeaders do connection
                Headers headers = exchange.getRequestHeaders();
                Set<Map.Entry<String, List<String>>> entrySet = headers.entrySet();
                for (Map.Entry<String, List<String>> entry : entrySet) {
                    String headerKeyName = entry.getKey();
//                System.out.println(headerKeyName);
                    List<String> headerValues = entry.getValue();
                    for (String value : headerValues) {
    //                    System.out.println(headerKeyName + ": " + value);
                        if (headerKeyName != null) {
                            conn.addRequestProperty(headerKeyName, value);
                        }
                    }
                }
    //            for (String key : headers.keySet()) {
    //                conn.setRequestProperty(key, headers.get(key).get(0));
    //            }
                conn.setRequestMethod(exchange.getRequestMethod());
                conn.setInstanceFollowRedirects(false);

                //          Przepisanie body
                if (exchange.getRequestMethod().equals("POST")) {

                    conn.setDoOutput(true);

                    byte[] requestBodyBytes = streamToBytes(exchange.getRequestBody());
                    conn.getOutputStream().write(requestBodyBytes);
                    OutputStream os = conn.getOutputStream();
                    os.write(requestBodyBytes);
                    os.close();
                    updateStatystykRequest(requestBodyBytes, addres, stats);
                }

    //            Przepisanie ConnectionHeaders do exchange ResponseHeaders
                Map headerFields = conn.getHeaderFields();
                for (Iterator iterator = headerFields.keySet().iterator(); iterator.hasNext();) {
                    String key = (String) iterator.next();
                    List values = (List) headerFields.get(key);
                    for (int i = 0; i < values.size(); i++) {
                        Object v = values.get(i);
                        if (key != null && !key.equalsIgnoreCase("Transfer-Encoding"))
                            exchange.getResponseHeaders().add(key, v.toString());
                    }
                }
                exchange.getResponseHeaders().set("Via", "localhost:8888");
                exchange.getResponseHeaders().set("Client-Ip", exchange.getRemoteAddress().toString());


                InputStream stream;
                if (conn.getResponseCode() < 400) {
                    stream = conn.getInputStream();
                } else {
                    stream = conn.getErrorStream();
                }
                byte[] streamBytes = streamToBytes(stream);
                stream.close();
                exchange.sendResponseHeaders(conn.getResponseCode(), streamBytes.length);
                OutputStream os = exchange.getResponseBody();
                updateStatystykResponse(streamBytes, addres, stats);
                os.write(streamBytes);
                os.close();


//              Zapisywanie do pliku CSV
                CSVWriter csvWriter = new CSVWriter(new FileWriter("/Users/Szalbik/Downloads/ServerProxy/statystics.csv"));
                zapisDoPliku(stats, csvWriter);
                csvWriter.flush();
                csvWriter.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void odczytZPliku(Map<String, List<Integer>> stats, List<String[]> records) {
            for (String[] record : records) {
                String[] stat = record[1].split("[\\[\\]\\,\\s]+");
                List<Integer> statystics = new ArrayList<>();
                for (int i = 1; i < 4; i++) {
                    statystics.add(parseInt(stat[i]));
                }
                stats.put(record[0], statystics);
            }
        }

        private void zapisDoPliku(Map<String, List<Integer>> stats, CSVWriter csvWriter) {
            for (Map.Entry statEntry : stats.entrySet()) {
                csvWriter.writeNext(new String[]{statEntry.getKey().toString(), statEntry.getValue().toString()});
            }
        }

        private void updateStatystykResponse(byte[] responseBody, URL addres, Map<String, List<Integer>> stats) throws IOException {
            if (stats.keySet().contains(addres.toString())) {
                for (Map.Entry<String, List<Integer>> statEntry : stats.entrySet()) {
                    if (statEntry.getKey().equals(addres.toString())) {
                        List<Integer> recordStats = statEntry.getValue();
                        int iloscWejscia = recordStats.get(0)+1;
                        recordStats.set(0, iloscWejscia);

//                        byte[] reqestBody = streamToBytes(os);
                        int reqbodylength = recordStats.get(2);
                        recordStats.set(2, reqbodylength + responseBody.length);
                        statEntry.setValue(recordStats);
                    }
                }
            } else {
                List<Integer> defaultStats = new ArrayList<>();
                defaultStats.add(1); //  Pierwszy wiersz

//                    byte[] reqestBody = streamToBytes(exchange.getRequestBody());
                defaultStats.add(0); // Drugi wiersz requestBody.length

                defaultStats.add(0 + responseBody.length); // Trzeci wiersz

                stats.put(addres.toString(), defaultStats);
            }
        }

        private void updateStatystykRequest(byte[] requestBody, URL addres, Map<String, List<Integer>> stats) throws IOException {
            if (stats.keySet().contains(addres.toString())) {
                for (Map.Entry<String, List<Integer>> statEntry : stats.entrySet()) {
                    if (statEntry.getKey().equals(addres.toString())) {
                        List<Integer> recordStats = statEntry.getValue();
                        int iloscWejscia = recordStats.get(0)+1;
                        recordStats.set(0, iloscWejscia);

//                        byte[] reqestBody = streamToBytes(os);
                        int reqbodylength = recordStats.get(1);
                        recordStats.set(1, reqbodylength + requestBody.length);
                        statEntry.setValue(recordStats);
                    }
                }
            } else {
                List<Integer> defaultStats = new ArrayList<>();
                defaultStats.add(1); //  Pierwszy wiersz

//                    byte[] reqestBody = streamToBytes(exchange.getRequestBody());
                defaultStats.add(0 + requestBody.length); // Drugi wiersz requestBody.length

                defaultStats.add(0); // Trzeci wiersz

                stats.put(addres.toString(), defaultStats);
            }
        }

        public static byte[] streamToBytes(InputStream input) throws IOException
        {
            byte[] buffer = new byte[8192];
            int bytesRead;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while ((bytesRead = input.read(buffer)) != -1)
            {
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        }
    }
}