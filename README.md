# Spring Batch No-Offset ItemReader

> Spring Batch의 Offset 기반 페이징 성능 저하를 해결하기 위한 **Keyset(No-Offset) Pagination** 커스텀 ItemReader

## 프로젝트 통합 시 안내

이 코드는 독립적으로 관리되는 Reader 컴포넌트입니다.
Spring Batch 프로젝트에 통합 시 **패키지 경로와 클래스명을 프로젝트 구조에 맞게 변경**하여 사용합니다.

```
예시)
com.project.batch.reader.QuerydslPagingItemReader   -- 핵심 Reader
com.project.batch.reader.JobPostingReaderFactory     -- Job별 Reader 생성 팩토리 (ItemReader.java)
```

---

## 왜 만들었는가

Spring Boot 프레임워크를 도입하면서 기존 배치 로직을 Spring Batch로 이관했습니다. 코드 구조와 가독성은 올라갔지만, **기존 코드 대비 성능 차별점을 찾을 수 없었습니다.**

chunk 단위로 각 파트별 소요 시간을 로그로 찍어본 결과:

```
Chunk 1  → Read: 120ms  | Process: 45ms | Write: 30ms
Chunk 10 → Read: 750ms  | Process: 44ms | Write: 31ms
Chunk 50 → Read: 3,800ms | Process: 47ms | Write: 30ms
```

**Read만 선형 증가.** 원인을 추적하니 `JpaPagingItemReader`의 `doReadPage()`가 내부적으로 `OFFSET` 기반 쿼리를 실행하고 있었고, 이는 기존 레거시 코드를 JPA로 옮긴 것에 불과했습니다.

```sql
-- Offset: 페이지가 진행될수록 느려짐
SELECT * FROM table ORDER BY id LIMIT 10000 OFFSET 490000

-- No-Offset: 항상 일정한 성능
SELECT * FROM table WHERE id > :lastId ORDER BY id LIMIT 10000
```

`JpaPagingItemReader`는 offset 계산이 내부에 강하게 결합되어 부분 오버라이드가 불가능하여, `AbstractPagingItemReader`를 직접 상속해 구현했습니다.

---

## 성능

테스트 데이터 50만 건, chunk size 10,000건, Step 실행 로그의 read 소요 시간 기준

```
Offset 방식 — 총 DB 스캔: 12,750,000행
  10,000 × (1 + 2 + ... + 50) = 12,750,000

No-Offset 방식 — 총 DB 스캔: 500,000행
  10,000 × 50 = 500,000

→ Offset이 25.5배 더 많은 행을 스캔
→ Read 단계 소요 시간 약 80% 이상 단축
→ 시간 복잡도 O(n²) → O(n)
```

데이터가 증가할수록 개선 폭은 더 커집니다.

---

## 포함된 파일

| 파일 | 설명 |
|------|------|
| `QuerydslPagingItemReader.java` | 재사용 가능한 No-Offset ItemReader |
| `ItemReader.java` | 특정 엔티티에 대한 팩토리 클래스 예시 |

---

## 사용 방법

### 1. QuerydslPagingItemReader 추가

프로젝트에 `QuerydslPagingItemReader.java`를 추가합니다.

### 2. 팩토리 또는 직접 생성

```java
public static ItemReader<JobPosting> create(
        JPAQueryFactory jpaQueryFactory, int pageSize) {

    return new QuerydslPagingItemReader<>(
            jpaQueryFactory,
            (qf, lastId) -> {
                JPAQuery<JobPosting> query = qf.selectFrom(jobPosting)
                        .where(jobPosting.sentToDiscord.eq(false));

                if (lastId != null) {
                    query.where(jobPosting.id.gt(lastId));
                }
                return query.orderBy(jobPosting.id.asc());
            },
            JobPosting::getId,
            pageSize
    );
}
```

### 3. Step에 등록

```java
@Bean
public Step myStep(ItemReader<JobPosting> reader,
                   ItemProcessor<JobPosting, JobPosting> processor,
                   ItemWriter<JobPosting> writer) {
    return stepBuilderFactory.get("myStep")
            .<JobPosting, JobPosting>chunk(10000)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
}
```

---

## 핵심 파라미터

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `jpaQueryFactory` | `JPAQueryFactory` | Querydsl 쿼리 생성용 팩토리 |
| `queryFunction` | `BiFunction<JPAQueryFactory, ID, JPAQuery<T>>` | 쿼리 정의 람다. `lastId`가 null이 아니면 `WHERE id > lastId` 조건 추가 필요. **반드시 ID 기준 오름차순 정렬 포함** |
| `idExtractor` | `Function<T, ID>` | 엔티티에서 페이징 기준 ID 추출 (예: `JobPosting::getId`) |
| `pageSize` | `int` | 한 번에 조회할 건수. chunk size와 동일하게 설정 권장 |

---

## 설계 포인트

| 결정 | 이유 |
|------|------|
| 제네릭 `<T, ID extends Comparable>` | 엔티티/ID 타입에 독립적으로 재사용 가능 |
| `BiFunction`으로 쿼리 외부 주입 | 비즈니스 조건이 바뀌어도 Reader 수정 불필요 |
| `Function`으로 ID 추출 분리 | 복합키 등 다양한 ID 전략 지원 |
| `AbstractPagingItemReader` 상속 | Spring Batch 표준 준수, 기존 Step/Chunk 설정과 호환 |
| `ExecutionContext` 재시작 지원 | 배치 장애 시 lastId 기반으로 중단 지점부터 재처리 |
| `volatile` + `CopyOnWriteArrayList` | 멀티스레드 Step 환경에서 스레드 안전성 확보 |

---

## 기술 스택

`Spring Batch` `Spring Boot` `Java 17` `Querydsl 5.x` `JPA`
