package cloud.operon.platform.service;

import cloud.operon.platform.domain.Notification;
import cloud.operon.platform.domain.Operino;
import cloud.operon.platform.domain.OperinoComponent;
import cloud.operon.platform.domain.enumeration.HostingType;
import cloud.operon.platform.domain.enumeration.OperinoComponentType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import cloud.operon.platform.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

/**
 * Service Interface for managing Operino.
 */
public interface OperinoService {

    /**
     * Save a operino.
     *
     * @param operino the entity to save
     * @return the persisted entity
     */
    Operino save(Operino operino);

    /**
     * Get all the operinos.
     *
     * @param pageable the pagination information
     * @return the list of entities
     */
    Page<Operino> findAll(Pageable pageable);

    /**
     * Get the "id" operino if the current user is the owner or has ADMIN role
     *
     * @param id the id of the entity
     * @return the entity
     */
    Operino verifyOwnershipAndGet(Long id);

    /**
     * Get the "id" operino.
     *
     * @param id the id of the entity
     * @return the entity
     */
    Operino findOne(Long id);

    /**
     * Delete the "id" operino.
     *
     * @param id the id of the entity
     */
    void delete(Long id);

    /**
     * Search for the operino corresponding to the query.
     *
     * @param query    the query of the search
     * @param pageable the pagination information
     * @return the list of entities
     */
    Page<Operino> search(String query, Pageable pageable);

    Map<String, String> getConfigForOperino(Operino operino);

    Notification sendNotification(Notification notification);

    Page<Notification> getNotifications(Pageable pageable);
    
    /**
     * Create Operino if the service is configured to do so
     */
    Operino createOperino(Operino operino);

     /**
     * Static utility method to create an Operino with the given parameters
     */
    static Operino createOperino(String name, User user, boolean active, boolean provision) {
        Operino operino = new Operino();
        operino.setName(name);
        operino.setUser(user);
        operino.setActive(active);
        operino.setProvision(provision);
        return operino;
    }
}
