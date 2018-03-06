package cloud.operon.platform.service.impl;

import cloud.operon.platform.domain.Operino;
import cloud.operon.platform.domain.Patient;
import cloud.operon.platform.domain.User;
import cloud.operon.platform.service.MailService;
import cloud.operon.platform.service.OperinoProvisioner;
import cloud.operon.platform.service.OperinoService;
import cloud.operon.platform.service.util.ParameterCollector;
import cloud.operon.platform.service.util.ThinkEhrRestClient;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.commons.codec.binary.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Service Implementation for provisioning Operinos.
 */
@Service
@Transactional
@RabbitListener(queues = "operinos")
@ConfigurationProperties(prefix = "provisioner", ignoreUnknownFields = false)
public class OperinoProvisionerImpl implements InitializingBean, OperinoProvisioner {

    private final Logger log = LoggerFactory.getLogger(OperinoProvisionerImpl.class);
    String domainUrl;
    String cdrUrl;
    String explorerUrl;
    String subjectNamespace;
    String username;
    String password;
    String agentName;
    List<Patient> patients = new ArrayList<>();

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    OperinoService operinoService;

    @Autowired
    ThinkEhrRestClient thinkEhrRestClient;

    @Autowired
    MailService mailService;

    @Override
    @RabbitHandler
    public void receive(@Payload Operino operino) {
        try {
            provision(operino);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void provision(Operino project) throws URISyntaxException {
        HttpHeaders headers = createAuthenticatedHeaders();
        RestTemplate restTemplate = new RestTemplate();

        createDomain(restTemplate, headers, project.getDomain(), project.getName());

        createUser(restTemplate, headers, project.getDomain(), project.getUser());

        headers.setContentType(MediaType.APPLICATION_XML);
        uploadTemplate(headers, project);


    }

    private void createDomain(RestTemplate restTemplate, HttpHeaders headers, String domainName, String projectName) throws URISyntaxException {
        URI uri = new URI(cdrUrl + "/admin/rest/v1/domains");
        String requestJson = "{" +
            "\"blocked\": \"false\"," +
            "\"description\": \"" + projectName + "\"," +
            "\"name\": \"" + domainName + "\"," +
            "\"systemId\": \"" + domainName + "\"" +
            "}";
        HttpEntity<Object> request = new HttpEntity<>(requestJson, headers);

        restTemplate.postForLocation(uri, request);
    }

    private void createUser(RestTemplate restTemplate, HttpHeaders headers, String domainName, User domainUser) throws URISyntaxException {
        URI uri = new URI(cdrUrl + "/admin/rest/v1/users");

        String requestJson = "{" +
            "\"username\": \"" + domainUser.getLogin() + "\"," +
            "\"password\": \"" + domainUser.getPassword() + "\"," +
            "\"name\": \"" + domainUser.getLogin() + "\"," +
            "\"externalRef\": null," +
            "\"blocked\": false," +
            "\"defaultDomain\": \"" + domainName + "\"," +
            "\"roles\": {\"" + domainName + "\": [\"ROLE_ADMIN\"]}," +
            "\"superUser\": false" +
            "}";
        HttpEntity<Object> request = new HttpEntity<>(requestJson, headers);

        restTemplate.postForLocation(uri, request);
    }

    private HttpHeaders createAuthenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        String auth = calculateBase64Auth(username, password);
        headers.add("Authorization", "Basic " + auth);
        return headers;
    }

    private void provisionLegacy(@Payload Operino operino) {
        log.info("Received operino {}", operino);
        // now build variables for posting to ehrscape provisioner
        String base64Creds = calculateBase64Auth(username, password);
        // set headers
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Basic " + base64Creds);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // create Map of data to be posted for domain creation
        Map<String, String> config = operinoService.getConfigForOperino(operino);
        // post data to api
        HttpEntity<Map<String, String>> getRequst = new HttpEntity<>(headers);
        log.info("getRequest = " + getRequst);

        try {
            ResponseEntity<List> getResponse = restTemplate.exchange(domainUrl, HttpMethod.GET, getRequst, List.class);
            log.debug("getResponse = " + getResponse);
            if (getResponse.getStatusCode() == HttpStatus.OK && !getResponse.getBody().contains(operino.getDomain())) {

                HttpEntity<Map<String, String>> domainRequest = new HttpEntity<>(config, headers);
                log.debug("domainRequest = " + domainRequest);
                ResponseEntity<String> domainResponse = restTemplate.postForEntity(domainUrl, domainRequest, String.class);
                log.debug("domainResponse = " + domainResponse);
                if (domainResponse.getStatusCode() == HttpStatus.CREATED) {
                    uploadTemplate(headers, operino);

                    // if entire provisioning has been completed
                    Map<String, String> configMap = operinoService.getConfigForOperino(operino);
                    try {
                        ParameterCollector parameterCollector = new ParameterCollector(config, getRequst);

                        JSONObject postMan = parameterCollector.getPostmanConfig();
                        ByteArrayResource postman = new ByteArrayResource(postMan.toString().getBytes());

                        String md = parameterCollector.getWorkspaceMarkdown();
                        ByteArrayResource markdown = new ByteArrayResource(md.toString().getBytes());

                        mailService.sendProvisioningCompletionEmail(operino, configMap, postman, markdown);
                    } catch (JSONException | UnsupportedEncodingException e) {
                        log.warn("Could not create attachments");
                        mailService.sendProvisioningCompletionEmail(operino, configMap, null, null);
                    }
                } else {
                    log.error("Unable to create domain for operino {}", operino);
                }
            } else {
                log.error("Unable to verify domain {} does not already exist. So operino will NOT be processed.", operino.getDomain());
            }
        } catch (RestClientException e) {
            log.error("Error looking up domain using domain id {}. Nested exception is : {}", operino.getDomain(), e);
        }
    }

    private void uploadTemplate(HttpHeaders headers, @Payload Operino operino) {
        // upload various templates - we have to upload at least on template as work around fo EhrExplorer bug
        thinkEhrRestClient.uploadTemplate(headers, "sample_requests/problems/problems-template.xml");
        // now if user has requested provisioning, we upload other templates and generated data
        if (operino.getProvision()) {
            thinkEhrRestClient.uploadTemplate(headers, "sample_requests/allergies/allergies-template.xml");
            thinkEhrRestClient.uploadTemplate(headers, "sample_requests/lab-results/lab-results-template.xml");
            thinkEhrRestClient.uploadTemplate(headers, "sample_requests/orders/orders-template.xml");
            thinkEhrRestClient.uploadTemplate(headers, "sample_requests/vital-signs/vital-signs-template.xml");
            thinkEhrRestClient.uploadTemplate(headers, "sample_requests/procedures/procedures-template.xml");
            createPatients(headers);
        }
    }

    private void createPatients(HttpHeaders headers) {
        // now call ehrscape_provisioner endpoint with map and a parameter for data file
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (Patient p : patients) {
            try {
                // create patient
                String patientId = thinkEhrRestClient.createPatient(headers, p);
                log.debug("Created patient with Id = {}", patientId);
                // create ehr
                String ehrId = thinkEhrRestClient.createEhr(p, headers, subjectNamespace, p.getNhsNumber(), agentName);
                log.debug("Created ehr with Id = {}", ehrId);
                // now upload compositions against each template loaded above
                // -- first process vital signs template compositions
                // create composition file path
                String compositionPath = "sample_requests/vital-signs/vital-signs-composition.json";
                String compositionId = thinkEhrRestClient.createComposition(headers, ehrId, "Vital Signs Encounter (Composition)", agentName, compositionPath);
                log.debug("Created composition with Id = {}", compositionId);
                // -- first process allergy template compositions
                for (int i = 1; i < 7; i++) {
                    // create composition file path
                    compositionPath = "sample_requests/allergies/AllergiesList_" + i + "FLAT.json";
                    compositionId = thinkEhrRestClient.createComposition(headers, ehrId, "IDCR Allergies List.v0", agentName, compositionPath);
                    log.debug("Created composition with Id = {}", compositionId);
                }
                // -- next process lab order compositions
                for (int i = 1; i < 13; i++) {
                    // create composition file path
                    compositionPath = "sample_requests/orders/IDCR_Lab_Order_FLAT_" + i + ".json";
                    compositionId = thinkEhrRestClient.createComposition(headers, ehrId, "IDCR - Laboratory Order.v0", agentName, compositionPath);
                    log.debug("Created composition with Id = {}", compositionId);
                }
                // -- next process procedure compositions
                for (int i = 1; i < 7; i++) {
                    // create composition file path
                    compositionPath = "sample_requests/procedures/IDCR_Procedures_List_FLAT_" + i + ".json";
                    compositionId = thinkEhrRestClient.createComposition(headers, ehrId, "IDCR Procedures List.v0", agentName, compositionPath);
                    log.debug("Created composition with Id = {}", compositionId);
                }
                // -- next process lab result compositions
                for (int i = 1; i < 13; i++) {
                    // create composition file path
                    compositionPath = "sample_requests/lab-results/IDCR_Lab_Report_INPUT_FLAT_" + i + ".json";
                    compositionId = thinkEhrRestClient.createComposition(headers, ehrId, "IDCR - Laboratory Test Report.v0", agentName, compositionPath);
                    log.debug("Created composition with Id = {}", compositionId);
                }

            } catch (IOException e) {
                log.error("Error processing json to submit for composition. Nested exception is : ", e);
            }
        }
    }

    private String calculateBase64Auth(String user, String pass) {
        String plainCreds = user + ":" + pass;
        byte[] plainCredsBytes = plainCreds.getBytes();
        byte[] base64CredsBytes = Base64.encodeBase64(plainCredsBytes);
        return new String(base64CredsBytes);
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        // verify we are able to connect to thinkehr instance
        String base64Creds = calculateBase64Auth(username, password);
        // set headers
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Basic " + base64Creds);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // connect to api
        HttpEntity<Map<String, String>> getRequest = new HttpEntity<>(headers);
        log.info("getRequest = " + getRequest);
        ResponseEntity<List> getResponse;
        try {
            getResponse = restTemplate.exchange(domainUrl, HttpMethod.GET, getRequest, List.class);

            log.info("getResponse = " + getResponse);
            if (getResponse == null || getResponse.getStatusCode() != HttpStatus.OK) {
                log.error("Unable to connect to ThinkEHR backend specified by: " + domainUrl);
            } else {
                // load patients from files
                for (int i = 0; i < 5; i++) {
                    patients.addAll(loadPatientsList("data/patients" + (i + 1) + ".csv"));
                    log.info("Loaded {} patients from file {}", patients.size(), "data/patients" + i + ".csv");
                }
                log.info("Final number of patients = {}", patients.size());
            }
        } catch (RestClientException e) {
            log.error("Unable to connect to ThinkEHR backend specified by: " + domainUrl);
        }
    }

    public List<Patient> loadPatientsList(String fileName) {
        try {
            CsvSchema bootstrapSchema = CsvSchema.emptySchema().withHeader();
            CsvMapper mapper = new CsvMapper();
            MappingIterator<Patient> mappingIterator = mapper.reader(Patient.class).with(bootstrapSchema).readValues(OperinoProvisionerImpl.class.getClassLoader().getResourceAsStream(fileName));
            return mappingIterator.readAll();
        } catch (Exception e) {
            log.error("Error occurred while loading object list from file " + fileName, e);
            return Collections.emptyList();
        }
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setExplorerUrl(String explorerUrl) {
        this.explorerUrl = explorerUrl;
    }

    public void setCdrUrl(String cdrUrl) {
        this.cdrUrl = cdrUrl;
    }

    public void setDomainUrl(String domainUrl) {
        this.domainUrl = domainUrl;
    }

    public void setSubjectNamespace(String subjectNamespace) {
        this.subjectNamespace = subjectNamespace;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

}
