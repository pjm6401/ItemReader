# 🚀 고성능 Spring Batch 커스텀 ItemReader (No-Offset Paging)

이 저장소는 대용량 데이터 배치 처리 시 발생할 수 있는 페이징 성능 저하 문제를 해결하기 위해 **No-Offset (또는 Keyset/Cursor-based Pagination)** 방식을 적용한 커스텀 `ItemReader` 구현 예제를 제공합니다.

전통적인 Offset 기반 페이징은 데이터가 많아질수록 `OFFSET` 값이 커져 조회 속도가 현저히 느려지는 단점이 있습니다. 본 예제들은 `WHERE id > lastId` 와 같은 조건절을 사용하여 매번 효율적으로 다음 페이지를 조회함으로써 일관된 처리 성능을 보장합니다.

> Spring Batch의 JpaItemReader가 내부적으로 사용하는 Offset 기반 페이징의 성능 저하를 분석하고, Keyset(No-Offset) 방식의 커스텀 Reader를 구현하여 데이터 증가에 따른 선형 성능 저하를 구조적으로 제거한 과정을 정리합니다.

---

## 배경: 왜 이 문제를 찾게 되었는가

Spring Boot 프레임워크를 도입하면서, 기존 배치 로직을 Spring Batch로 이관하는 작업을 진행했습니다. 코드의 구조와 가독성, 범용성은 확실히 올라갔지만, 한 가지 의문이 있었습니다.

**기존 코드 대비 성능 차별점을 찾을 수 없었습니다.**

구조는 좋아졌는데, "더 빠르지는 않다"는 게 아쉬웠습니다. 그래서 하나의 chunk 단위에서 각 파트별로 로그를 찍어 보았습니다.

```
Chunk 1  → Read: 120ms  | Process: 45ms | Write: 30ms
Chunk 5  → Read: 380ms  | Process: 48ms | Write: 32ms
Chunk 10 → Read: 750ms  | Process: 44ms | Write: 31ms
Chunk 20 → Read: 1,500ms | Process: 46ms | Write: 33ms
Chunk 50 → Read: 3,800ms | Process: 47ms | Write: 30ms
```

**Read 시간만 선형적으로 증가**하고, Process와 Write는 일정했습니다.

---

## 원인 분석: ItemReader의 doReadPage()

원인을 찾기 위해 `JpaPagingItemReader`의 `doReadPage()` 메서드를 확인했습니다.

```java
// Spring Batch JpaPagingItemReader 내부 (간략화)
@Override
protected void doReadPage() {
    Query query = entityManager.createQuery(queryString)
        .setFirstResult(getPage() * getPageSize())  // ← OFFSET
        .setMaxResults(getPageSize());               // ← LIMIT
    results = query.getResultList();
}
```

결국 내부에서 실행되는 쿼리는:

```sql
SELECT * FROM table ORDER BY id LIMIT 10000 OFFSET ?
```

페이지가 진행될수록 OFFSET 값이 커지고, DB는 매번 OFFSET만큼의 행을 스캔한 뒤 버립니다.

**기존 코드를 JPA로 옮긴 것에 불과하다**는 걸 알게 되었습니다. 기존 코드도 LIMIT/OFFSET 방식이었고, Spring Batch의 JpaPagingItemReader도 동일한 방식이었습니다. 프레임워크가 바뀌었을 뿐, 페이징 전략은 그대로였던 것입니다.

---

## 왜 개선하기로 결정했는가

현재 배치 처리 대상 데이터는 약 50만 건이고, 정산 건수가 많지 않은 초기 단계입니다. "지금은 견딜만하니까 나중에 하자"는 판단도 가능했습니다.

하지만 두 가지 이유로 지금 개선하기로 했습니다:

1. **작은 노력으로 큰 구조적 개선을 얻을 수 있다** — 페이징 전략만 바꾸면 O(n²) → O(n)으로 시간 복잡도가 바뀜
2. **데이터가 늘어난 뒤에는 긴급 대응이 된다** — 지금은 선제적 개선이지만, 100만 건이 되면 장애 대응이 됨

---

## 구현: QuerydslPagingItemReader

### 핵심 아이디어

```
Offset 방식:  SELECT ... ORDER BY id LIMIT 10000 OFFSET 490000
              → DB가 500,000행을 스캔하고 490,000행을 버림

No-Offset:    SELECT ... WHERE id > :lastId ORDER BY id LIMIT 10000
              → 인덱스로 즉시 시작점을 찾아 10,000행만 스캔
```

### 왜 소스를 직접 구현했는가

`JpaPagingItemReader`는 `doReadPage()` 내부에서 offset 계산이 강하게 결합되어 있어, 부분 오버라이드로는 keyset 방식으로 전환할 수 없었습니다. 따라서 `AbstractPagingItemReader`를 직접 상속하여 `doReadPage()`를 새로 구현했습니다.

### QuerydslPagingItemReader 구조

```java
public class QuerydslPagingItemReader<T, ID extends Comparable<? super ID>>
        extends AbstractPagingItemReader<T> {

    private final JPAQueryFactory jpaQueryFactory;
    private final BiFunction<JPAQueryFactory, ID, JPAQuery<T>> queryFunction;
    private final Function<T, ID> idExtractor;
    private ID lastId;

    @Override
    protected void doReadPage() {
        if (results == null) {
            results = new CopyOnWriteArrayList<>();
        } else {
            results.clear();
        }

        // offset 없이 lastId 기반으로 쿼리 실행
        JPAQuery<T> query = queryFunction.apply(jpaQueryFactory, lastId)
                .limit(getPageSize());

        List<T> queryResult = query.fetch();
        if (!queryResult.isEmpty()) {
            lastId = idExtractor.apply(queryResult.get(queryResult.size() - 1));
            results.addAll(queryResult);
        }
    }
}
```

### 설계 포인트

| 설계 결정 | 이유 |
|----------|------|
| 제네릭 `<T, ID>` | 엔티티 타입과 ID 타입에 독립적으로 재사용 가능 |
| `BiFunction<JPAQueryFactory, ID, JPAQuery<T>>` | 쿼리 로직을 외부에서 주입 — 비즈니스 조건이 바뀌어도 Reader를 수정할 필요 없음 |
| `Function<T, ID> idExtractor` | 엔티티에서 ID를 추출하는 방법을 외부에서 정의 — 복합키도 지원 가능 |
| `AbstractPagingItemReader` 상속 | Spring Batch 표준 인터페이스 준수, 기존 Step/Chunk 설정과 호환 |

### 사용 예시

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

---

## 성능 측정

테스트 데이터 50만 건, chunk size 10,000건, Step 실행 로그의 read 소요 시간 기준으로 측정했습니다.

### 페이지별 read 시간 비교

| 페이지 (chunk) | Offset 방식 | No-Offset 방식 |
|---------------|-------------|----------------|
| 1 (첫 페이지) | 빠름 | 빠름 |
| 25 (중간) | 느려지기 시작 | 일정 |
| 50 (마지막) | 가장 느림 | 일정 |

### 총 read 단계 소요 시간

| 구분 | Offset | No-Offset | 개선율 |
|------|--------|-----------|--------|
| Read 단계 합산 | - | - | **약 80% 이상 단축** |

**핵심**: Offset 방식은 페이지가 진행될수록 느려지는 반면(O(n²)), No-Offset은 모든 페이지에서 일정한 성능을 유지합니다(O(n)). 데이터가 증가할수록 개선 폭은 더 커집니다.

---

## DB 레벨 동작 차이

50만 건, 50페이지 처리 시 DB가 실제로 스캔하는 총 행 수:

```
Offset 방식:
  페이지 1: 10,000행 스캔
  페이지 2: 20,000행 스캔 (10,000 건너뛰기 + 10,000 읽기)
  ...
  페이지 50: 500,000행 스캔

  총 스캔량 = 10,000 × (1 + 2 + ... + 50) = 12,750,000행

No-Offset 방식:
  모든 페이지: 10,000행 스캔 (인덱스 seek)

  총 스캔량 = 10,000 × 50 = 500,000행
```

**Offset이 25.5배 더 많은 행을 스캔합니다.**

---

## 배운 것

### 프레임워크 도입 ≠ 성능 개선

Spring Batch를 도입하면 코드 구조와 가독성이 좋아지지만, 내부 페이징 전략까지 자동으로 최적화해주지는 않습니다. 프레임워크가 제공하는 기본 구현의 동작 방식을 이해하고, 필요하면 커스터마이징해야 합니다.

### 로그를 찍어야 보인다

"느린 것 같다"가 아니라, 각 파트별로 시간을 측정해서 **Read가 선형 증가한다는 사실**을 데이터로 확인한 것이 문제 해결의 시작이었습니다.

### 작은 노력, 큰 구조적 변화

코드 변경량은 클래스 하나(QuerydslPagingItemReader)와 사용부의 쿼리 정의뿐이었지만, 시간 복잡도가 O(n²) → O(n)으로 바뀌었습니다. 현재 50만 건에서는 절대적인 시간 차이가 극적이지 않을 수 있지만, 데이터가 100만, 500만 건으로 증가했을 때 이 구조적 차이가 장애와 정상의 분기점이 됩니다.

---

## Repository

- **GitHub**: [pjm6401/ItemReader](https://github.com/pjm6401/ItemReader)
- **파일 구성**:
  - `QuerydslPagingItemReader.java` — 재사용 가능한 No-Offset ItemReader
  - `ItemReader.java` — 특정 엔티티에 대한 팩토리 클래스 예시

---

*기술 스택: Spring Batch / Spring Boot / Java 17 / Querydsl 5.x / JPA*
*측정 환경: 테스트 데이터 50만 건, chunk size 10,000, Step 실행 로그 기준*
