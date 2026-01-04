# Backend PR Review Rules

  ## Architecture Rules (CRITICAL)

  ### Domain/Entity Exposure
  - Repository or Entity should NOT be exposed outside its service layer
  - Always return DTOs from service methods

  ### Domain DTO Required
  - Every Entity exposed via API must have a corresponding DTO

  ### No DB Calls in Converters
  - Repository calls inside DTO converters are forbidden
  - Fetch all data before conversion

  ### Bulk Converters Only
  - Converters must support bulk conversion
  - Never call converter in a loop: use `converter.convertAll(list)`

  ### Builder Rules
  - No logic inside builder methods (no ternary operators)
  - Verify correct source variable is used

  ## JPA/Domain Rules (HIGH)

  ### Index Required
  - All repository query fields must have @Index
  - New domain objects must define indexes

  ### Enum Handling
  - Use @Enumerated(EnumType.STRING) for all enums
  - Equality: use `EnumType.VALUE == variable` not `.equals()`

  ### Entity Standards
  - equals/hashCode required in all domain entities
  - New entities must extend Auditable

  ## Code Quality (MEDIUM)

  ### Collections
  - Use Set for unique elements, List for ordered/duplicates
  - Never initialize collections to null
  - Methods returning collections must not return null
  - Filter nulls from streams before collect

  ### Utilities
  - Use BooleanUtils.isTrue() to avoid NPE
  - Use BigDecimalUtils.getValueOrZero()
  - Round decimals only in DTO layer

  ### Elasticsearch
  - Index via @TransactionalEventListener(phase = AFTER_COMMIT)
  - Never index inside @Transactional method

  ### Event Listeners
  - Listeners must not contain business logic
  - Call service methods from listeners

  ### API Validations
  - All APIs must validate input (even if UI validates)
  - Include negative checks, status validations

  ### Exceptions
  - Include business context in error messages
  - Never swallow stack traces


