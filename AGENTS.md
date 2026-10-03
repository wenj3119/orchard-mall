# Project instructions

- This is a modular monolith for one merchant per deployment. Never add cross-merchant tenancy or automatic payment splitting without an explicit product decision.
- Backend: Java 21, Spring Boot 3.4.5, Maven, MySQL 8.4, Flyway. Frontends: React/TypeScript; admin uses Ant Design, miniapp uses Taro.
- Keep money as integer minor units (fen) or `BigDecimal` with explicit scale. Never use floating-point money.
- Public APIs may expose only published products and active SKUs. Every admin API requires server-side authentication.
- Real payment credentials and merchant secrets come from environment or secret storage; never return them to clients or commit them.
- Use `docs/architecture.md`, `docs/domain-model.md`, and `docs/roadmap.md` for design and phase status. Update README when implementation status changes.
- Local start: copy `.env.example` to `.env`, set `ADMIN_INIT_PASSWORD`, run `docker compose -f deploy/compose.yml --env-file .env up -d`, then `cd backend && ./mvnw spring-boot:run`; in separate shells run `cd admin-web && npm run dev` and `cd miniapp && npm run dev:weapp` or `npm run dev:alipay`.
- Validation: `cd backend && ./mvnw test`, `cd admin-web && npm run typecheck && npm run build`, `cd miniapp && npm run build:weapp && npm run build:alipay`.
