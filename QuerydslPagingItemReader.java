package com.batch.Custom.Reader;

import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.AbstractPagingItemReader;
import org.springframework.util.Assert;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

// ID 타입이 정렬 가능하도록 제네릭 타입 추가 <T, ID>
public class QuerydslPagingItemReader<T, ID extends Comparable<? super ID>> extends AbstractPagingItemReader<T> {

    private final JPAQueryFactory jpaQueryFactory;
    // 쿼리 함수가 마지막 ID를 파라미터로 받을 수 있도록 BiFunction으로 변경
    private final BiFunction<JPAQueryFactory, ID, JPAQuery<T>> queryFunction;
    // 엔티티에서 ID를 추출하는 함수
    private final Function<T, ID> idExtractor;

    // 마지막으로 조회한 ID를 저장할 변수, volatile로 스레드 안전성 확보
    private volatile ID lastId;

    private static final String LAST_ID_KEY = "lastId";

    public QuerydslPagingItemReader(
            JPAQueryFactory jpaQueryFactory,
            BiFunction<JPAQueryFactory, ID, JPAQuery<T>> queryFunction,
            Function<T, ID> idExtractor,
            int pageSize
    ) {
        Assert.notNull(jpaQueryFactory, "JPAQueryFactory must not be null");
        Assert.notNull(queryFunction, "Query function must not be null");
        Assert.notNull(idExtractor, "ID extractor function must not be null");

        this.jpaQueryFactory = jpaQueryFactory;
        this.queryFunction = queryFunction;
        this.idExtractor = idExtractor;
        setPageSize(pageSize);
        setName(getClass().getSimpleName());
    }

    @Override
    protected void doReadPage() {
        if (results == null) {
            results = new CopyOnWriteArrayList<>();
        } else {
            results.clear();
        }

        // 쿼리 함수에 jpaQueryFactory와 마지막 ID(lastId)를 전달하여 쿼리 생성
        JPAQuery<T> query = queryFunction.apply(jpaQueryFactory, lastId)
                .limit(getPageSize()); // offset 없이 limit만 사용

        List<T> queryResult = query.fetch();
        if (!queryResult.isEmpty()) {
            // 조회된 결과의 마지막 아이템에서 ID를 추출하여 lastId를 업데이트
            lastId = idExtractor.apply(queryResult.get(queryResult.size() - 1));
            results.addAll(queryResult);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doOpen() throws Exception {
        super.doOpen();
        // ExecutionContext에 저장된 lastId가 있으면 복원 (배치 재시작 시)
        ExecutionContext ec = getExecutionContext();
        if (ec != null && ec.containsKey(LAST_ID_KEY)) {
            this.lastId = (ID) ec.get(LAST_ID_KEY);
        } else {
            this.lastId = null;
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        super.update(executionContext);
        // 현재 lastId를 ExecutionContext에 저장 (장애 시 재시작 지점으로 사용)
        if (lastId != null) {
            executionContext.put(LAST_ID_KEY, lastId);
        }
    }
}
