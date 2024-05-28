FROM ghcr.io/graalvm/jdk-community:21

RUN microdnf install gzip && \
    curl -fsSL https://get.pulumi.com | sh && \
    curl -fL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d > /bin/scala-cli && \
    chmod +x /bin/scala-cli && \
    curl -LO https://dl.k8s.io/release/v1.30.0/bin/linux/amd64/kubectl && \
    mv kubectl /bin/kubectl && \
    chmod +x /bin/kubectl

ENV PATH="/root/.pulumi/bin:$PATH"

RUN pulumi plugin install language scala 0.4.0-SNAPSHOT --server github://api.github.com/VirtusLab/besom

COPY app.main /app/main

ENTRYPOINT java -jar /app/main
