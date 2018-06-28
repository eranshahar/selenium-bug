
import com.google.common.base.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.assertj.core.api.Java6Assertions.fail;

public class SeleniumBug {

    private static final Logger logger = LoggerFactory.getLogger(SeleniumBug.class);
    public static final String SELENIUM_VERSION = "3.6.0";

    private static GenericContainer seleniumContainer;

    @BeforeClass
    public static void beforeClass() throws InterruptedException {
        seleniumContainer = new GenericContainer("selenium/standalone-chrome:" + SELENIUM_VERSION);
        seleniumContainer.withFileSystemBind("/tmp", "/images", BindMode.READ_WRITE);
        seleniumContainer.start();
        TimeUnit.SECONDS.sleep(5);
    }

    @AfterClass
    public static void afterClass() {
        if (seleniumContainer != null) seleniumContainer.stop();
    }

    @Test
    public void testAlerts() throws InterruptedException {
        int cycleCounter = 1;
        while (true) {
            logger.info("Cycle number: {}", cycleCounter++);
            Map<Long, String> images = new ConcurrentHashMap();
            LongAdder nThreads = new LongAdder();
            File imagesDir = new File("/tmp");
            nThreads.add(20);
            CountDownLatch countDownLatch = new CountDownLatch(nThreads.intValue());
            ExecutorService executorService = Executors.newFixedThreadPool(nThreads.intValue());
            IntStream.range(1, nThreads.intValue() + 1).forEach(i ->
                    executorService.execute(() -> {
//                        try {
                        URL htmlUrl = createHtml(i, imagesDir.toPath(), countDownLatch);
                        if (htmlUrl != null) {
                            try {
                                URL imgUrl = createImage(htmlUrl, i, imagesDir.toPath(), images);
                            }
                            catch (Throwable t) {
                                if (!images.values().contains(i)) {
                                    logger.info("decrementing, account: {}", i);
                                    nThreads.decrement();
                                }
                                else {
                                    logger.error("Not decrementing, account: {}", i);
                                }
                                logger.error("Failed to create image for account: {}. images: {}", i, t, images);
                            }
                            finally {
                                countDownLatch.countDown();
                            }
                        }
                    }));
            if (!countDownLatch.await(40, TimeUnit.SECONDS)) {
                fail("fail");
            }
            assertThat(images).hasSize(nThreads.intValue());
        }
    }

    URL createHtml(int accountId, Path path, CountDownLatch countDownLatch) {
        try {
            String html = "<!DOCTYPE html>\n" +
                    "<html lang=\"en\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <title>Eran</title>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "Eran%s%s%s%s%s\n" +
                    "</body>\n" +
                    "</html>";
            String fileName = "/" + accountId + "." + "html";
            String filePath = path.toAbsolutePath() + fileName;
            File htmlFile = new File(filePath);
            FileUtils.write(htmlFile, String.format(html, accountId + "-" + RandomStringUtils.randomAlphanumeric(accountId),
                    RandomStringUtils.randomAlphanumeric(accountId), RandomStringUtils.randomAlphanumeric(accountId),
                    RandomStringUtils.randomAlphanumeric(accountId), RandomStringUtils.randomAlphanumeric(accountId)), Charsets.UTF_8);
            URL urlToHtml = new File("/images" + fileName).toURI().toURL();
            logger.info("urlToHtml: {}", urlToHtml);
            return urlToHtml;
        }
        catch (Throwable e) {
            logger.error("Failed to create html.", e);
            countDownLatch.countDown();
            return null;
        }
    }

    @SuppressWarnings("Duplicates")
    URL createImage(URL htmlUrl, int accountId, Path path, Map images) throws Exception {
        RemoteWebDriver webDriver = null;
        try {
            String imgPath = path.toAbsolutePath() + "/" + "Image" + accountId + "." + "png";
            Integer mappedPort = seleniumContainer.getMappedPort(4444);
            webDriver = new RemoteWebDriver(new URL("http://localhost:" + mappedPort + "/wd/hub"), DesiredCapabilities.chrome());
            webDriver.manage().timeouts().setScriptTimeout(20, TimeUnit.SECONDS)
                    .pageLoadTimeout(20, TimeUnit.SECONDS).implicitlyWait(30, TimeUnit.SECONDS);
            webDriver.manage().window().setSize(new Dimension(500, 500));
            webDriver.navigate().to(htmlUrl);
            webDriver.switchTo().defaultContent();
            webDriver.manage().window().setSize(new Dimension(500, 500));
            Actions builder = new Actions(webDriver);
            builder.moveByOffset(500, 500);
            builder.perform();
            byte[] screenshotAs = webDriver.getScreenshotAs(OutputType.BYTES);
            FileUtils.writeByteArrayToFile(new File(imgPath), screenshotAs);
            URL urlToImage = new File(imgPath).toURI().toURL();
//            URL url = uploadFileToS3andGetUrl(accountId, accountId, imgPath);
            Checksum checksum = new CRC32();
            checksum.update(screenshotAs, 0, screenshotAs.length);
            long checksumValue = checksum.getValue();
            Integer previousAccountId = (Integer) images.putIfAbsent(checksumValue, accountId);
            if (previousAccountId != null) {
                logger.info("Found the bug !!! snapshot duplication!!! previous account id: {}, accountId: {}, image path: {}, duplication image path: {}",
                        previousAccountId, accountId, path.toAbsolutePath() + "/" + "Image" + previousAccountId + "." + "png", imgPath);
                TimeUnit.SECONDS.sleep(5);
                System.exit(1);
            }
            logger.info("Finish to generate image and copy to {}, url: {}", imgPath, urlToImage);
            return urlToImage;
        }
        finally {
            if (webDriver != null) {
                webDriver.close();
                webDriver.quit();
            }
        }
    }

}
