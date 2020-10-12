package br.com.exemplo.dataingestion.adapters.datastores;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import br.com.exemplo.dataingestion.domain.entities.Lancamento;
import br.com.exemplo.dataingestion.domain.repositories.LancamentoListRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class LancamentoListRepositoryImpl implements LancamentoListRepository {

    private final RestHighLevelClient client;
    private ObjectMapper jacksonObjectMapper = new ObjectMapper();
    @Value("${processamento.elasticsearch.index:extrato_alias}")
    private String index;

    @Value("${spring.elasticsearch.rest.refresh-policy:false}")
    private String refreshPolicy;


    Counter itemsCounter = Metrics.globalRegistry.counter("elasticsearch.items", "Type", "Record");
    Counter createdCounter = Metrics.globalRegistry.counter("elasticsearch.reported", "Type", "Record");
    Counter updatedCounter = Metrics.globalRegistry.counter("elasticsearch.updated", "Type", "Record");
    Counter failedCounter = Metrics.globalRegistry.counter("elasticsearch.failed", "Type", "Record");

    Timer elasticSearchTimer = Metrics.globalRegistry.timer("elasticsearch.request", "Type", "Record");
    AtomicLong elasticSearchResponseTimeGauge = Metrics.globalRegistry.gauge("elasticsearch.took", new AtomicLong(0));
    
    AtomicInteger batchSizeGauge = Metrics.globalRegistry.gauge("elasticsearch.batch", new AtomicInteger(0));

    Timer totalMethodTimer = Metrics.globalRegistry.timer("elasticsearch.write", "Type", "Record");


    AtomicInteger totalSentRecords = new AtomicInteger(0);   
    AtomicInteger totalErrorRecords = new AtomicInteger(0);

    @Override
    public List<String> save(List<Lancamento> lancamentoList) {
        Timer.Sample methodTimer = Timer.start(Metrics.globalRegistry);
        BulkRequest request = new BulkRequest();
        request.setRefreshPolicy(refreshPolicy);
        for (Lancamento lancamento : lancamentoList) {
            try {
                // extrato-202010
                String indexName = "extrato-".concat(lancamento.getDataContabilLancamento().substring(0, 7));
                request.add(new IndexRequest(indexName)
                    .id(lancamento.getNumeroIdentificacaoLancamentoConta().toString())
                    .source(jacksonObjectMapper.writeValueAsString(lancamento), XContentType.JSON)
                );
            } catch (JsonProcessingException e) {
                log.error("failed to process item", lancamento);
            }
        }

        try {
            Timer.Sample requestTimer = Timer.start(Metrics.globalRegistry);
            BulkResponse bulkResponse = client.bulk(request, RequestOptions.DEFAULT);
            elasticSearchResponseTimeGauge.set(bulkResponse.getIngestTookInMillis());
            requestTimer.stop(elasticSearchTimer);


            int requestSent = lancamentoList.size();
            AtomicInteger requestErrors = new AtomicInteger(0);
            AtomicInteger requestCreated = new AtomicInteger(0);
            AtomicInteger requestUpdated = new AtomicInteger(0);

            bulkResponse.forEach(
                bulkItemResponse -> {
                    if (bulkItemResponse.isFailed()) {
                        requestErrors.incrementAndGet();
                        log.error("Operation has errors {} ", bulkItemResponse.getFailureMessage());
                    } else switch (bulkItemResponse.getOpType()) {
                        case INDEX:
                        case CREATE:
                            requestCreated.incrementAndGet();
                            break;
                        case UPDATE:
                            requestUpdated.incrementAndGet();
                            break;
                        case DELETE:
                    }
                }
            );

            itemsCounter.increment(requestSent);
            createdCounter.increment(requestCreated.intValue());
            updatedCounter.increment(requestUpdated.intValue());
            failedCounter.increment(requestErrors.intValue());
            batchSizeGauge.set(requestSent);
            log.info(
                "Records so far: {} (added {}, created {}, updated {}, errors {}, total errors {})", 
                totalSentRecords.addAndGet(requestSent),
                requestSent,
                requestCreated,
                requestUpdated,
                requestErrors,
                totalErrorRecords.addAndGet(requestErrors.intValue())
            );
        } catch (IOException e) {
            log.error("failed to send bulk request {}", e.getMessage());
        }
        methodTimer.stop(totalMethodTimer);
        return null;
    }
}
