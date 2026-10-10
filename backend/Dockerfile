FROM node:22-alpine
WORKDIR /app
COPY package.json server.js ./
ENV PORT=8080 DATA_FILE=/data/db.json
VOLUME /data
EXPOSE 8080
CMD ["node","server.js"]
