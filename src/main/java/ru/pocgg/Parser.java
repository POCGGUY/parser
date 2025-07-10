package ru.pocgg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

@Component
public class Parser {
    private Boolean isRunning = true;
    private static final Logger log = LoggerFactory.getLogger(Parser.class);

    public ArrayList<ArrayList<String>> parsedImagesInfo = new ArrayList<>();
    public ArrayList<String> requests = new ArrayList<>();

    private final String targetURL = "https://api.arthive.com/v2.0/works.search?offset=0&extends=works" +
            ".alt_media_ids,works.media_id,works.counters,works.properties,works.collection_id,works.infos,works" +
            ".description,filters.uri,works.aset_ids,works.artist_ids&count=10&artist_id=65&order=default&";

    private final ArrayList<String> imageVersionsKeys = new ArrayList<>(Arrays.asList("o", "h", "s", "v"));
    private final ArrayList<String> imageAxisKeys = new ArrayList<>(Arrays.asList("x", "y"));
    private final ArrayList<String> imageResKeys = new ArrayList<>(Arrays.asList("0", "10", "20", "40", "80",
            "100", "200", "400", "800", "1000", "1200", "1400", "1800", "2000", "2200", "2400", "2800"));

    private int managedRequestsCount = 0;
    private int managedSuccessfulRequestsCount = 0;
    private int managedFailedRequestsCount = 0;

    @PostConstruct
    public void init() {
        Thread mainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                parse();
                buildRequests();
                downloadImages();
            }
        });
        Thread managerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Scanner scanner = new Scanner(System.in);
                scanner.nextLine();
                isRunning = false;
                scanner.close();
            }
        });
        mainThread.start();
        managerThread.start();
    }

    public void parse() {
        try (Playwright pw = Playwright.create()) {
            APIRequestContext http = pw.request().newContext();
            APIResponse resp = http.get(targetURL);
            if (!isRunning) {
                log.info("Парсер останавливается");
                return;
            }
            if (!resp.ok()) {
                throw new IOException("Ошибка при запросе: " + resp.statusText());
            }

            String json = resp.text();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            JsonNode data = root.path("data");
            JsonNode mediaArray = data.path("media");

            for (JsonNode item : mediaArray) {
                if (!isRunning) {
                    log.info("Парсер останавливается");
                    return;
                }
                String mediaId = item.get("media_id").asText();
                JsonNode itemData = item.path("data");
                String version = itemData.get("version").asText();
                String bigVersion = itemData.get("version_big").asText();
                String origVersion = itemData.get("version_orig").asText();
                parsedImagesInfo.add(new ArrayList<>(Arrays.asList(mediaId, version, bigVersion, origVersion)));
            }
        } catch (Exception e) {
            log.error("Ошибка в parse(): ", e);
            throw new RuntimeException(e);
        }
    }

    public void buildRequests() {
        if (!isRunning) {
            log.info("Парсер останавливается");
            return;
        }

        if (parsedImagesInfo.isEmpty()) {
            throw new RuntimeException("Нет информации об изображениях чтобы сформировать запрос");
        }

        parsedImagesInfo.forEach(currentImage -> {
            imageVersionsKeys.forEach(currentVersion -> {
                imageAxisKeys.forEach(currentAxis -> {
                    imageResKeys.forEach(currentResKey -> {
                        buildUrlAndAddToRequests(currentImage, currentVersion, currentAxis, currentResKey);
                    });
                });
            });
        });
        log.info("Запросы успешно созданы, общее количество запросов: {}", requests.size());
    }

    public void downloadImages() {
        if (requests.isEmpty()) {
            throw new RuntimeException("Нет запросов на загрузку");
        }
        try (Playwright pw = Playwright.create()) {
            APIRequestContext http = pw.request().newContext();

            for (String request : requests) {
                if (!isRunning) {
                    log.info("Парсер останавливается");
                    return;
                }
                displayInfo();
                URI uri = URI.create(request);
                Path urlPath = Paths.get(uri.getPath().substring(1));

                if (Files.exists(urlPath)) {
                    managedSuccessfulRequestsCount++;
                    continue;
                }

                APIResponse resp = null;
                int retries = 3;
                int retriesCount = 0;
                while (retriesCount < retries) {
                    try {
                        if (!isRunning) {
                            log.info("Парсер останавливается");
                            return;
                        }
                        resp = http.get(request);
                        break;
                    } catch (TimeoutError e) {
                        log.warn("Превышено время ожидания ответа, повтор запроса...");
                        retriesCount++;
                    }
                }

                if (resp == null) {
                    log.error("Не удалось получить ответ от сервера после {} попыток", retries);
                    log.error("Провалившийся запрос : {}", request);
                }

                if (!resp.ok()) {
                    managedFailedRequestsCount++;
                } else {
                    byte[] data = resp.body();
                    Files.createDirectories(urlPath.getParent());
                    Files.write(urlPath, data);
                    managedSuccessfulRequestsCount++;
                }
                managedRequestsCount++;
            }
        } catch (Exception e) {
            log.error("Ошибка в downloadImages(): ", e);
            throw new RuntimeException(e);
        }
    }

    private void buildUrlAndAddToRequests(ArrayList<String> currentImage,
                                          String currentVersion,
                                          String currentAxis,
                                          String currentRes) {
        String base = "https://arthive.com/res/media/img/";
        String baseSecondPart = "/work/";
        String baseLastPart = ".jpg";
        for (int i = 1; i < currentImage.size(); i++) {
            String request = base +
                    currentVersion +
                    currentAxis +
                    currentRes +
                    baseSecondPart +
                    currentImage.get(i) +
                    "/" +
                    currentImage.get(0) +
                    baseLastPart;
            requests.add(request);
        }
    }

    private void displayInfo() {
        int percentage = (int) (((double) managedRequestsCount / requests.size()) * 100.0);
        String managedSymbol = "\u2588";
        String notManagedSymbol = "\u2591";
        String progressBarManaged = managedSymbol.repeat(percentage / 2);
        String progressBarNotManaged = notManagedSymbol.repeat((int) Math.round((100 - percentage) / 2.0));

        log.info("Прогресс: {}%: [{}{}] Всего: {}, Битых: {}, Успешно:{}",
                percentage, progressBarManaged, progressBarNotManaged,
                requests.size(), managedFailedRequestsCount, managedSuccessfulRequestsCount);
    }
}
