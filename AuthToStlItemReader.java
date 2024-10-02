package com.ILK.batch.Custom.Reader;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;
import org.springframework.batch.item.database.AbstractPagingItemReader;
import org.springframework.batch.item.database.orm.JpaQueryProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.ClassUtils;

import com.ILK.batch.DTO.FromToDTO;
import com.ILK.batch.Entity.AuthEntity;



public class AuthToStlItemReader<T> extends AbstractPagingItemReader<T> {

	private EntityManagerFactory entityManagerFactory;

	private EntityManager entityManager;

	private final Map<String, Object> jpaPropertyMap = new HashMap<>();

	private String queryString;

	private JpaQueryProvider queryProvider;

	private Map<String, Object> parameterValues;
	
	private FromToDTO fromToDTO;
	private String operator =">=";
	private boolean isFirstPage = true;
	private String lastId = null;
	//트랜잭션 사용 여부
	private boolean transacted = true;//default value

	public AuthToStlItemReader() {
		setName(ClassUtils.getShortName(AuthToStlItemReader.class));
	}

	/*
	 *  사용자가 별도의 쿼리 제공자(JpaQueryProvider)를 설정하지 않았을 때, 
	 *  entityManager.createQuery(queryString)를 호출하여 queryString에 설정된 JPQL 쿼리 문자열을 사용하여 쿼리(Query) 객체를 생성
	 */
	public void setFromToDTO(FromToDTO fromToDTO) {
		this.fromToDTO = fromToDTO;
	}
	
	
	protected Query createQuery(boolean isFirst) {
		if (queryProvider == null) {
			
			setQueryString("SELECT auth FROM AuthEntity auth WHERE auth.id.transRequestId "+operator+" :lastId");
			Query query = entityManager.createQuery(queryString)
                    .setParameter("lastId", isFirst ? "00000000000000" : lastId);
			if(isFirstPage) {
				isFirstPage = false;
				operator=">";
			}
		    return query;
		}
		else {
			return queryProvider.createQuery();
		}
	}

	/**
	 * @param queryString JPQL query string
	 */
	public void setQueryString(String queryString) {
		this.queryString = queryString;
	}

	/**
	 * @param queryProvider JPA query provider
	 */
	public void setQueryProvider(JpaQueryProvider queryProvider) {
		this.queryProvider = queryProvider;
	}

	@Override
	protected void doOpen() throws Exception {
		super.doOpen();

		entityManager = entityManagerFactory.createEntityManager(jpaPropertyMap);
		if (entityManager == null) {
			throw new DataAccessResourceFailureException("Unable to obtain an EntityManager");
		}
		// set entityManager to queryProvider, so it participates
		// in JpaPagingItemReader's managed transaction
		if (queryProvider != null) {
			queryProvider.setEntityManager(entityManager);
		}

	}

	@Override
	@SuppressWarnings("unchecked")
	protected void doReadPage() {

		EntityTransaction tx = null;

	    if (transacted) {
	        tx = entityManager.getTransaction();
	        tx.begin();
	        entityManager.flush();
	        entityManager.clear();
	    }

	    Query query = createQuery(isFirstPage).setFirstResult(0).setMaxResults(getPageSize()); // 페이지 계산 로직을 제거

	    if (parameterValues != null) {
	        for (Map.Entry<String, Object> me : parameterValues.entrySet()) {
	            query.setParameter(me.getKey(), me.getValue());
	        }
	    }

	    if (results == null) {
	        results = new CopyOnWriteArrayList<>();
	    } else {
	        results.clear();
	    }

	    List<T> queryResult = query.getResultList();
	    for (T entity : queryResult) {
	        if (!transacted) {
	            entityManager.detach(entity);
	        }
	        results.add(entity);
	    }

	    if (!results.isEmpty()) {
	        // 마지막으로 읽은 데이터의 ID를 업데이트
	    	AuthEntity authEntity = (AuthEntity) results.get(results.size()-1);
	    	//System.out.println(authEntity.toString());
	        lastId = authEntity.getAuthPK().getTransRequestId();
	        //System.out.println(lastId);
	    }

	    if (transacted) {
	        tx.commit();
	    }
		
		if(isFirstPage) {
			isFirstPage = false;
		}
	}

	@Override
	protected void doJumpToPage(int itemIndex) {
	}

	@Override
	protected void doClose() throws Exception {
		entityManager.close();
		super.doClose();
	}
	

	public void setEntityManagerFactory(EntityManagerFactory entityManagerFactory) {
		this.entityManagerFactory = entityManagerFactory;
	}

	
	public void setParameterValues(Map<String, Object> parameterValues) {
		this.parameterValues = parameterValues;
	}
	
	
	public void setTransacted(boolean transacted) {
		this.transacted = transacted;
	}	

	@Override
	public void afterPropertiesSet() throws Exception {
		super.afterPropertiesSet();

		if (queryProvider == null) {
			//Assert.notNull(entityManagerFactory, "EntityManager is required when queryProvider is null");
			//Assert.hasLength(queryString, "Query string is required when queryProvider is null");
		}
	}
}
