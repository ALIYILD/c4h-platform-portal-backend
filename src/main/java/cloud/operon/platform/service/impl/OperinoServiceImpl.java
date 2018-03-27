package cloud.operon.platform.service.impl;

import cloud.operon.platform.domain.Notification;
import cloud.operon.platform.domain.Operino;
import cloud.operon.platform.domain.OperinoComponent;
import cloud.operon.platform.domain.enumeration.HostingType;
import cloud.operon.platform.domain.enumeration.NotificationStatus;
import cloud.operon.platform.domain.enumeration.OperinoComponentType;
import cloud.operon.platform.repository.NotificationRepository;
import cloud.operon.platform.repository.OperinoRepository;
import cloud.operon.platform.repository.search.OperinoSearchRepository;
import cloud.operon.platform.security.SecurityUtils;
import cloud.operon.platform.service.OperinoService;
import cloud.operon.platform.service.UserService;
import cloud.operon.platform.service.util.ThinkEhrRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery;

/**
 * Service Implementation for managing Operino.
 */
@Service
@Transactional
@ConfigurationProperties(prefix = "operinoService", ignoreUnknownFields = false)
public class OperinoServiceImpl implements OperinoService {
    private final Logger log = LoggerFactory.getLogger(OperinoServiceImpl.class);

    private final OperinoRepository operinoRepository;
    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final OperinoSearchRepository operinoSearchRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ThinkEhrRestClient thinkEhrRestClient;

    private Boolean createNewOperinoWithComponents;

    public OperinoServiceImpl(OperinoRepository operinoRepository,
                              NotificationRepository notificationRepository,
                              OperinoSearchRepository operinoSearchRepository,
                              UserService userService,
                              RabbitTemplate rabbitTemplate,
                              ThinkEhrRestClient thinkEhrRestClient) {
        this.operinoRepository = operinoRepository;
        this.operinoSearchRepository = operinoSearchRepository;
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.rabbitTemplate = rabbitTemplate;
        this.thinkEhrRestClient = thinkEhrRestClient;
    }

    /**
     * Save a operino.
     *
     * @param operino the entity to save
     * @return the persisted entity
     */
    @Override
    public Operino save(Operino operino) {
        log.debug("Request to save Operino : {}", operino);
        operino.setUser(userService.getUserWithAuthoritiesByLogin(SecurityUtils.getCurrentUserLogin()).get());
        // assign all components to operino before save - cascade will save components automatically
        for (OperinoComponent component : operino.getComponents()) {
            component.setOperino(operino);
        }
        Operino result = operinoRepository.save(operino);
        operinoSearchRepository.save(result);
        rabbitTemplate.convertAndSend("operinos", result);
        log.info("Sent off result to rabbitmq");
        return result;
    }

    /**
     * Get all the operinos.
     *
     * @param pageable the pagination information
     * @return the list of entities
     */
    @Override
    @Transactional(readOnly = true)
    public Page<Operino> findAll(Pageable pageable) {
        log.debug("Request to get all Operinos");
        if (userService.isAdmin()) {
            Page<Operino> result = operinoRepository.findAll(pageable);
            return result;
        } else {
            Page<Operino> result = operinoRepository.findByUserIsCurrentUser(SecurityUtils.getCurrentUserLogin(), pageable);
            return result;
        }
    }

    /**
     * Get one operino by id.
     *
     * @param id the id of the entity
     * @return the entity
     */
    @Override
    @Transactional(readOnly = true)
    public Operino verifyOwnershipAndGet(Long id) {
        log.debug("Request to verify ownership and get Operino : {}", id);
        Operino operino = operinoRepository.findOneByUserAndId(SecurityUtils.getCurrentUserLogin(), id);
        if (operino != null) {
            return operino;
        } else if (userService.isAdmin()) {
            return operinoRepository.findOne(id);
        } else {
            return null;
        }
    }


    /**
     * Get one operino by id.
     *
     * @param id the id of the entity
     * @return the entity
     */
    @Override
    @Transactional(readOnly = true)
    public Operino findOne(Long id) {
        log.debug("Request to get Operino : {}", id);
        return this.verifyOwnershipAndGet(id);
    }


    /**
     * Get one operino by id. No authorisation check is done!
     *
     * @param id the id of the entity
     * @return the entity
     */
    @Override
    @Transactional(readOnly = true)
    public Operino findOneNoAuth(Long id) {
        log.debug("Request to get Operino : {}", id);
        return operinoRepository.findOne(id);
    }

    /**
     * Delete the  operino by id.
     *
     * @param id the id of the entity
     */
    @Override
    public void delete(Long id) {
        log.debug("Request to delete Operino : {}", id);
        // veirfy ownership
        Operino operino = findOne(id);
        if (operino != null) {
            // first truncate domain
            thinkEhrRestClient.truncateDomain(operino.getDomain());
            operinoRepository.delete(id);
            operinoSearchRepository.delete(id);
        } else {
            log.error("Unable to find operino {} to delete", id);
        }
    }

    /**
     * Search for the operino corresponding to the query.
     *
     * @param query the query of the search
     * @return the list of entities
     */
    @Override
    @Transactional(readOnly = true)
    public Page<Operino> search(String query, Pageable pageable) {
        log.debug("Request to search for a page of Operinos for query {}", query);
        Page<Operino> result = operinoSearchRepository.search(queryStringQuery(query), pageable);
        return result;
    }


    /**
     * Gets config associated with an operino
     *
     * @param operino the operino to get config for
     * @return the congig as a map
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, String> getConfigForOperino(Operino operino) {

        String name = operino.getUser().getFirstName() + operino.getUser().getLastName();
        if (name.length() < 1) {
            name = operino.getDomain();
        }

        String user = operino.getUser().getLogin() + "_" + operino.getDomain();
        String pass = operino.getUser().getPassword().substring(0, 12);

        // create Map of data to be posted for domain creation
        Map<String, String> data = new HashMap<>();
        data.put(DOMAIN, operino.getDomain());
        data.put(DOMAIN_SYSTEM_ID, operino.getDomain());
        data.put(USER_DISPLAY_NAME_OR_DOMAIN, name);
        data.put(OPERINO_NAME, operino.getName());
        data.put(USERNAME, user);
        data.put(PASSWORD, pass);
        data.put(BASE_URL, this.thinkEhrRestClient.getBaseUrl());

        return data;
    }

    @Override
    public Notification sendNotification(Notification notification) {
        // save notification
        notification.setStatus(NotificationStatus.INPROGRESS);
//        notification = notificationRepository.save(notification);
        rabbitTemplate.convertAndSend("notifications", notification);
        log.debug("Notification sent to rabbitmq");

        return notification;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Notification> getNotifications(Pageable pageable) {
        if (userService.isAdmin()) {
            Page<Notification> result = notificationRepository.findAll(pageable);
            return result;
        } else {
            Page<Notification> result = notificationRepository.findByUserIsCurrentUser(SecurityUtils.getCurrentUserLogin(), pageable);
            return result;
        }
    }

    /**
     * Creates and populates the given Operino with components of the specified types
     *
     * @param operino The Operino to be populated
     * @return The same Operino with default components of the specified types
     */
    public Operino addDefaultComponents(Operino operino) {
        log.debug("Creating Operino (with components = {})", createNewOperinoWithComponents);
        if (createNewOperinoWithComponents && operino.getComponents().size() == 0) {
            OperinoComponentType[] types = {OperinoComponentType.CDR, OperinoComponentType.DEMOGRAPHICS};
            for (OperinoComponentType type : types) {
                OperinoComponent component = new OperinoComponent();
                component.setType(type);
                component.setAvailability(true);
                component.setHosting(HostingType.NON_N3);
                component.setDiskSpace(Long.valueOf(String.valueOf(1000)));
                component.setRecordsNumber(Long.valueOf(String.valueOf(1000)));
                component.setTransactionsLimit(Long.valueOf(String.valueOf(1000)));
                operino.addComponent(component);
            }
        }
        return operino;
    }

    public void setCreateNewOperinoWithComponents(Boolean createNewOperinoWithComponents) {
        this.createNewOperinoWithComponents = createNewOperinoWithComponents;
    }
}
