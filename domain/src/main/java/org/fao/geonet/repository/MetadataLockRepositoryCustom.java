package org.fao.geonet.repository;

import org.fao.geonet.domain.User;

public interface MetadataLockRepositoryCustom {

    boolean lock(String id, User user);
    
    boolean isLocked(String id);
    
    boolean isLockedByUser(String id, User user);
    
    boolean unlock(String id);

}
