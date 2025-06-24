package ru.selsup;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;


public class CrptApi {
    private final TimeLimiter timeLimiter;
    private HttpClient httpClient;
    private Gson gson;
    private static String linkCreateDocument = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static String productGroup;
    private String token;
    private final ReentrantLock lock = new ReentrantLock();


    /** @param timeUnit единица измерения промежутка времени (секунда, минута etc)
     *  @param requestLimit максимальное количество запросов, за промежуток времени */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {

        this.timeLimiter = new TimeLimiter(timeUnit, requestLimit);

        /** Получаем на этапе авторизации, в данном классе уместно получить из класса авторизации, для примера просто строка */
        token = "token";

        productGroup = "clothes";

        createHttpClient();
        createGson();

    }


    private static class TimeLimiter{

        private final long period;
        private final int limit;
        private int count;
        private long last;

        public TimeLimiter(TimeUnit timeUnit, int requestLimit) {
            this.period = timeUnit.toMillis(1);
            this.limit = requestLimit;
            this.count = 0;
            this.last = System.currentTimeMillis();
        }

        public synchronized void control() throws InterruptedException {
            long now = System.currentTimeMillis();

            if (now - last >= period) {
                count = 0;
                last = now;
            }

            if (count >= limit) {
                long waitTime = last + period - now;
                if (waitTime > 0) {
                    Thread.sleep(waitTime);
                }
                count = 0;
                last = System.currentTimeMillis();
            }

            count++;
        }

    }


    @Getter
    @Setter
    private static class BodyPostDocumentToInputToTurnover {

        @SerializedName("document_format")
        private DocFormat documentFormat;

        @SerializedName("product_document")
        private String product_document;

        @SerializedName("product_group")
        private String productGroup;


        @SerializedName("signature")
        private String signature;

        @SerializedName("type")
        private DocumentToInputToTurnover.DocType docType;

        @Getter
        private enum DocFormat {
            MANUAL;
        }

    }


    @Getter
    @Setter
    private static class DocumentToInputToTurnover {

        @SerializedName("description")
        private Description description;

        @SerializedName("doc_id")
        private String docId;

        @SerializedName("doc_status")
        private String docStatus;

        @SerializedName("doc_type")
        private DocType docType;

        @SerializedName("importRequest")
        private boolean importRequest;

        @SerializedName("owner_inn")
        private String ownerInn;

        @SerializedName("participant_inn")
        private String participantInn;

        @SerializedName("producer_inn")
        private String producerInn;

        @SerializedName("production_date")
        private LocalDate productionDate;

        @SerializedName("production_type")
        private String productionType;

        @SerializedName("products")
        private Products[] products;

        @SerializedName("reg_date")
        private LocalDate regDate;

        @SerializedName("reg_number")
        private String regNumber;


        @Getter
        @Setter
        private static class Description {
            @SerializedName("participantInn")
            private String participantInn;
        }


        @Getter
        private enum DocType {
            LP_INTRODUCE_GOODS;
        }


        @Getter
        @Setter
        private static class Products {

            @SerializedName("certificate_document")
            private String certificateDocument;

            @SerializedName("certificate_document_date")
            private LocalDate certificateDocumentDate;

            @SerializedName("certificate_document_number")
            private String certificateDocumentNumber;

            @SerializedName("owner_inn")
            private String ownerInn;

            @SerializedName("producer_inn")
            private String producerInn;

            @SerializedName("production_date")
            private LocalDate productionDate;

            @SerializedName("tnved_code")
            private String tnvedCode;

            @SerializedName("uit_code")
            private String uitCode;

            @SerializedName("uitu_code")
            private String uituCode;
        }


        static class LocalDateSerializer implements JsonSerializer<LocalDate> {
            private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-d");

            @Override
            public JsonElement serialize(LocalDate localDate, Type srcType, JsonSerializationContext context) {
                return new JsonPrimitive(formatter.format(localDate));
            }
        }

        static class LocalDateDeserializer implements JsonDeserializer<LocalDate> {
            @Override
            public LocalDate deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                    throws JsonParseException {
                return LocalDate.parse(json.getAsString(),
                        DateTimeFormatter.ofPattern("yyyy-MM-d").withLocale(Locale.ENGLISH));
            }
        }

    }


    /** Создание документа для ввода в оборот товара, произведенного в РФ */
    public void createDocument(DocumentToInputToTurnover document, String signature) {
        System.out.println("startMethod, document : " + document);

        try {

            timeLimiter.control();

            lock.lock();

            String documentJsonString = gson.toJson(document);
            String docJsonStringEncode = encodeString(documentJsonString);

            BodyPostDocumentToInputToTurnover bodyPostDocumentToInputToTurnover =
                    getBodyPostDocumentToInputToTurnover(docJsonStringEncode, signature, document);

            StringEntity entityBodyPost = new StringEntity(gson.toJson(bodyPostDocumentToInputToTurnover));

            HttpPost httpPost = createHttpPost();
            httpPost.setEntity(entityBodyPost);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer ".concat(token));

            HttpResponse response = httpClient.execute(httpPost);

            System.out.println("success endMethod, document : " + document);

        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

        System.out.println("endMethod, document : " + document);
    }


    private BodyPostDocumentToInputToTurnover getBodyPostDocumentToInputToTurnover(String docJsonStringEncode, String signature, DocumentToInputToTurnover document) {

        BodyPostDocumentToInputToTurnover bodyPostDocumentToInputToTurnover = new BodyPostDocumentToInputToTurnover();
        bodyPostDocumentToInputToTurnover.setDocumentFormat(BodyPostDocumentToInputToTurnover.DocFormat.MANUAL);
        bodyPostDocumentToInputToTurnover.setProduct_document(docJsonStringEncode);
        bodyPostDocumentToInputToTurnover.setProductGroup(productGroup);
        bodyPostDocumentToInputToTurnover.setSignature(signature);
        bodyPostDocumentToInputToTurnover.setDocType(document.getDocType());

        return bodyPostDocumentToInputToTurnover;
    }

    private void createHttpClient() {
        httpClient = HttpClients.createDefault();
    }

    private HttpPost createHttpPost() {
        HttpPost httpPost = new HttpPost(linkCreateDocument.concat("?pg=").concat(productGroup));
        return httpPost;
    }

    private void createGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(LocalDate.class, new DocumentToInputToTurnover.LocalDateSerializer());
        gsonBuilder.registerTypeAdapter(LocalDate.class, new DocumentToInputToTurnover.LocalDateDeserializer());
        gson = gsonBuilder.setPrettyPrinting().create();
    }

    private String encodeString(String value) {
        byte[] bytesEncoded = Base64.encodeBase64(value.getBytes());
        return new String(bytesEncoded);
    }

    private String decodeString(String value) {
        byte[] valueDecoded = Base64.decodeBase64(value);
        System.out.println("Decoded value is : " + new String(valueDecoded));
        return new String(valueDecoded);
    }


    public static void main(String[] args) {

    }

}
