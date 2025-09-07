# 🚀 고성능 Spring Batch 커스텀 ItemReader (No-Offset Paging)

이 저장소는 대용량 데이터 배치 처리 시 발생할 수 있는 페이징 성능 저하 문제를 해결하기 위해 **No-Offset (또는 Keyset/Cursor-based Pagination)** 방식을 적용한 커스텀 `ItemReader` 구현 예제를 제공합니다.

전통적인 Offset 기반 페이징은 데이터가 많아질수록 `OFFSET` 값이 커져 조회 속도가 현저히 느려지는 단점이 있습니다. 본 예제들은 `WHERE id > lastId` 와 같은 조건절을 사용하여 매번 효율적으로 다음 페이지를 조회함으로써 일관된 처리 성능을 보장합니다.

## ✨ 포함된 ItemReader 목록

1.  **`QuerydslPagingItemReader`**: JPA와 Querydsl 환경에서 No-Offset 페이징을 손쉽게 구현할 수 있는 재사용 가능한 `ItemReader`입니다. Type-safe한 쿼리를 작성하면서 대용량 데이터를 안정적으로 처리할 수 있습니다.
2.  **`ItemReader`**: `QuerydslPagingItemReader`를 특정 엔티티(`JobPosting`)에 맞게 설정하고 생성하는 방법을 보여주는 팩토리 클래스 예제입니다.

---

## 💡 `QuerydslPagingItemReader` 가이드

###  주요 특징

*   **성능 최적화**: Offset을 사용하지 않고 마지막으로 조회된 데이터의 ID를 기반으로 다음 페이지를 조회하여 대용량 테이블에서도 빠른 속도를 유지합니다.
*   **Type-Safety**: Querydsl을 사용하여 컴파일 시점에 쿼리의 타입을 체크하므로 런타임 에러를 방지하고 안정성을 높입니다.
*   **유연한 쿼리 작성**: `BiFunction` 인터페이스를 통해 `ItemReader` 외부에서 자유롭게 조회 쿼리를 정의하고 주입할 수 있어, 복잡한 비즈니스 로직이나 동적 쿼리도 쉽게 적용 가능합니다.
*   **Spring Batch 표준 준수**: Spring Batch의 `AbstractPagingItemReader`를 상속하여 구현되었기 때문에, 기존 배치 아키텍처와 자연스럽게 통합됩니다.
