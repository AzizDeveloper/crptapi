package dev.aziz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.concurrent.TimedSemaphore;
import org.apache.http.HttpHeaders;
import org.hibernate.validator.constraints.Length;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final String TOKEN = "my_secret_token";
    private static final Doc.Product.ProductGroup PRODUCT_GROUP = Doc.Product.ProductGroup.CLOTHES;
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private final RequestUtil requestUtil;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit < 1) throw new IllegalArgumentException("request limit must be > 0, " + requestLimit);
        this.requestUtil = new RequestUtil(timeUnit, requestLimit);
    }

    public HttpResponse<String> createDoc(Doc doc, String signature) throws InterruptedException, IOException {
        requestUtil.tryAdd();

        synchronized (Doc.MAPPER) {
            JsonNode document = Doc.MAPPER.valueToTree(doc);

            HttpClient httpClient = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(URL))
                    .POST(HttpRequest.BodyPublishers.ofString(makeRequestBody(document, signature)))
                    .header("pg", PRODUCT_GROUP.value)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("Authorization", "Bearer " + TOKEN)
                    .build();

            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

    }

    private static String makeRequestBody(JsonNode document, String signature) {
        JSONObject json = new JSONObject();

        json.put("document_format", "MANUAL");
        json.put("product_document", encodeToBase64(document));
        json.put("product_group", PRODUCT_GROUP.value);
        json.put("signature", signature);
        json.put("type", "LP_INTRODUCE_GOODS");

        return json.toString();
    }

    private static String encodeToBase64(JsonNode node) {
        synchronized (ENCODER) {
            return ENCODER.encodeToString(node.toString().getBytes());
        }
    }

    public static class RequestUtil {
        private static final int PERIOD = 1;
        private final TimedSemaphore timedSemaphore;

        public RequestUtil(TimeUnit timeUnit, int requestLimit) {
            this.timedSemaphore = new TimedSemaphore(PERIOD, timeUnit, requestLimit);
        }

        private void tryAdd() throws InterruptedException {
            timedSemaphore.acquire();
        }

    }

    @Getter
    public static class Doc {
        public static final ObjectMapper MAPPER = new ObjectMapper();
        private static final String DATE_PATTERN = "yyyy-MM-dd";
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(DATE_PATTERN);

        @NotNull
        private final String doc_id;
        @NotNull
        private final String doc_status;
        @NotNull
        private final String doc_type;
        @Setter
        private String importRequest;
        @NotNull
        private final String owner_inn;
        @NotNull
        private final String participant_inn;
        @NotNull
        private final String producer_inn;
        @NotNull
        private final String production_date;
        @NotNull
        private final String production_type;
        private final String reg_date;
        private final String reg_number;
        private Description description;
        @Setter
        private List<Product> products;

        public Doc(String doc_id,
                   String doc_status,
                   String doc_type,
                   String importRequest,
                   String owner_inn,
                   String participant_inn,
                   String producer_inn,
                   Date production_date,
                   ProductionType production_type,
                   List<Product> products) {
            this.doc_id = doc_id;
            this.doc_status = doc_status;
            this.doc_type = doc_type;
            this.importRequest = importRequest;
            this.owner_inn = owner_inn;
            this.participant_inn = participant_inn;
            this.producer_inn = producer_inn;
            this.production_date = getStringDateFrom(production_date);
            this.production_type = production_type.value;
            this.description = new Description(producer_inn);
            this.products = products;
            this.reg_number = Utils.getRegNumber();
            this.reg_date = Utils.getRegDate();
        }

        public Doc(String doc_id,
                   String doc_status,
                   String doc_type,
                   String owner_inn,
                   String participant_inn,
                   String producer_inn,
                   Date production_date,
                   ProductionType production_type) {
            this.doc_id = doc_id;
            this.doc_status = doc_status;
            this.doc_type = doc_type;
            this.owner_inn = owner_inn;
            this.participant_inn = participant_inn;
            this.producer_inn = producer_inn;
            this.production_date = getStringDateFrom(production_date);
            this.production_type = production_type.value;
            this.products = new LinkedList<>();
            this.reg_number = Utils.getRegNumber();
            this.reg_date = Utils.getRegDate();
        }

        void addProduct(Product product) {
            this.products.add(product);
        }

        private static String getStringDateFrom(Date date) {
            if (date == null) return "";
            return DATE_FORMAT.format(date);
        }

        private static Date getDateFrom(String stringDate) {
            if (stringDate == null) return null;
            try {
                return DATE_FORMAT.parse(stringDate);
            } catch (ParseException e) {
                throw new RuntimeException(String.format("Failed parse from %s, position %d%n", stringDate, e.getErrorOffset()));
            }
        }

        public enum ProductionType {
            OWN_PRODUCTION("OWN_PRODUCTION"),
            CONTRACT_PRODUCTION("CONTRACT_PRODUCTION");
            private final String value;

            ProductionType(String value) {
                this.value = value;
            }

        }

        @Getter
        public static class Description {
            private final String participantInn;

            public Description(String participantInn) {
                this.participantInn = participantInn;
            }

        }

        private static class Utils {
            private static volatile int regNumber = 0;
            private static final String PATTERN = "YYYY-MM-DD'T'HH:mm:ss";
            private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(PATTERN);

            private static String getRegDate() {
                return FORMATTER.format(LocalDateTime.now(ZoneId.of("Europe/Moscow")));
            }

            private static synchronized String getRegNumber() {
                return String.valueOf(++regNumber);
            }

        }

        @Getter
        public static class Product {
            private static final int UIT_LENGTH = 38;
            private static final int UITU_LENGTH = 18;
            private static final int TNVED_CODE_LENGHT = 10;
            @Setter
            private String certificate_document;
            @Setter
            private String certificate_document_date;
            @Setter
            private String certificate_document_number;
            @NotNull
            private final String owner_inn;
            @NotNull
            private final String producer_inn;
            private String production_date;
            @NotNull
            @Length(min = TNVED_CODE_LENGHT, max = TNVED_CODE_LENGHT)
            private final String tnved_code;
            @Setter
            @Length(min = UIT_LENGTH, max = UIT_LENGTH)
            private String uit_code;
            @Setter
            @Length(min = UITU_LENGTH, max = UITU_LENGTH)
            private String uitu_code;

            public Product(CertificateDocument certificate_document,
                           Date certificate_document_date,
                           String certificate_document_number,
                           String owner_inn,
                           String producer_inn,
                           Date production_date,
                           String tnved_code,
                           String uit_code,
                           String uitu_code) {
                this.certificate_document = certificate_document.value;
                this.certificate_document_date = getStringDateFrom(certificate_document_date);
                this.certificate_document_number = certificate_document_number;
                this.owner_inn = owner_inn;
                this.producer_inn = producer_inn;
                this.production_date = getStringDateFrom(production_date);
                this.tnved_code = tnved_code;
                this.uit_code = uit_code;
                this.uitu_code = uitu_code;
            }

            public Product(String owner_inn, String producer_inn, String tnved_code, String uitOrUitu) {
                this.owner_inn = owner_inn;
                this.producer_inn = producer_inn;
                this.tnved_code = tnved_code;
                if (uitOrUitu.length() == UIT_LENGTH) this.uit_code = uitOrUitu;
                if (uitOrUitu.length() == UITU_LENGTH) this.uitu_code = uitOrUitu;
            }

            public enum CertificateDocument {
                CONFORMITY_CERTIFICATE("CONFORMITY_CERTIFICATE"),
                CONFORMITY_DECLARATION("CONFORMITY_DECLARATION");
                private final String value;

                CertificateDocument(String value) {
                    this.value = value;
                }

            }

            public enum ProductGroup {
                CLOTHES("clothes"),
                SHOES("shoes"),
                TOBACCO("tobacco"),
                PERFUMERY("perfumery"),
                TIRES("tires"),
                ELECTRONICS("electronics"),
                PHARMA("pharma"),
                MILK("milk"),
                BICYCLE("bicycle"),
                WHEELCHAIRS("wheelchairs");
                private final String value;

                ProductGroup(String value) {
                    this.value = value;
                }

            }

        }

    }

}
