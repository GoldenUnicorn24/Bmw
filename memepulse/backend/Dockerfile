FROM node:22-alpine
WORKDIR /srv
COPY package.json ./
RUN npm install --omit=dev --ignore-scripts --no-audit --no-fund && npm cache clean --force
COPY risk.mjs botPolicy.mjs server.mjs ./
RUN addgroup -g 10001 -S memepulse && adduser -u 10001 -S -G memepulse memepulse
USER memepulse
EXPOSE 8787
CMD ["node", "server.mjs"]
