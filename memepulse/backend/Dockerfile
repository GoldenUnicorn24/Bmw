FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --ignore-scripts --no-audit --no-fund
COPY *.mjs ./
COPY research ./research
RUN mkdir -p /app/private && chown -R node:node /app/private
USER node
EXPOSE 8787
CMD ["node", "server.mjs"]
