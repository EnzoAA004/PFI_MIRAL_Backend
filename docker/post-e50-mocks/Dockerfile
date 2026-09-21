FROM node:20-alpine
WORKDIR /app
COPY mock-ai.mjs mock-llm.mjs ./
EXPOSE 8000 8001
# The compose service picks which script to run via the SCRIPT build arg / command override.
CMD ["node", "mock-ai.mjs"]
