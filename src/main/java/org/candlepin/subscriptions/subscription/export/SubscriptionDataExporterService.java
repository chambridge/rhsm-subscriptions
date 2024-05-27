/*
 * Copyright Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.subscription.export;

import static org.candlepin.subscriptions.resource.ResourceUtils.ANY;

import com.redhat.swatch.configuration.registry.MetricId;
import com.redhat.swatch.configuration.registry.ProductId;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.candlepin.subscriptions.db.HypervisorReportCategory;
import org.candlepin.subscriptions.db.SubscriptionCapacityViewRepository;
import org.candlepin.subscriptions.db.model.BillingProvider;
import org.candlepin.subscriptions.db.model.ServiceLevel;
import org.candlepin.subscriptions.db.model.SubscriptionCapacityView;
import org.candlepin.subscriptions.db.model.SubscriptionCapacityViewMetric;
import org.candlepin.subscriptions.db.model.SubscriptionCapacityView_;
import org.candlepin.subscriptions.db.model.Usage;
import org.candlepin.subscriptions.exception.ExportServiceException;
import org.candlepin.subscriptions.export.DataExporterService;
import org.candlepin.subscriptions.export.DataMapperService;
import org.candlepin.subscriptions.export.ExportServiceRequest;
import org.candlepin.subscriptions.json.SubscriptionsExportJsonMeasurement;
import org.candlepin.subscriptions.utilization.api.model.ReportCategory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("capacity-ingress")
@AllArgsConstructor
public class SubscriptionDataExporterService
    implements DataExporterService<SubscriptionCapacityView> {
  static final String SUBSCRIPTIONS_DATA = "subscriptions";
  static final String PRODUCT_ID = "product_id";
  static final String METRIC_ID = "metric_id";
  static final String CATEGORY = "category";
  static final String PHYSICAL = "PHYSICAL";
  static final String HYPERVISOR = "HYPERVISOR";
  private static final Map<String, Function<String, Specification<SubscriptionCapacityView>>>
      FILTERS =
          Map.of(
              PRODUCT_ID,
              SubscriptionDataExporterService::handleProductIdFilter,
              "usage",
              SubscriptionDataExporterService::handleUsageFilter,
              CATEGORY,
              SubscriptionDataExporterService::handleCategoryFilter,
              "sla",
              SubscriptionDataExporterService::handleSlaFilter,
              METRIC_ID,
              SubscriptionDataExporterService::handleMetricIdFilter,
              "billing_provider",
              SubscriptionDataExporterService::handleBillingProviderFilter,
              "billing_account_id",
              SubscriptionDataExporterService::handleBillingAccountIdFilter);

  private final SubscriptionCapacityViewRepository repository;
  private final SubscriptionJsonDataMapperService jsonDataMapperService;
  private final SubscriptionCsvDataMapperService csvDataMapperService;

  @Override
  public boolean handles(ExportServiceRequest request) {
    return Objects.equals(request.getRequest().getResource(), SUBSCRIPTIONS_DATA);
  }

  @Override
  public Stream<SubscriptionCapacityView> fetchData(ExportServiceRequest request) {
    log.debug("Fetching data for {}", request.getOrgId());
    return repository.streamBy(extractExportFilter(request));
  }

  @Override
  public DataMapperService<SubscriptionCapacityView> getMapper(ExportServiceRequest request) {
    return switch (request.getFormat()) {
      case JSON -> jsonDataMapperService;
      case CSV -> csvDataMapperService;
    };
  }

  private Specification<SubscriptionCapacityView> extractExportFilter(
      ExportServiceRequest request) {
    Specification<SubscriptionCapacityView> criteria = Specification.where(null);
    if (request.getFilters() != null) {
      var filters = request.getFilters().entrySet();
      try {
        for (var entry : filters) {
          var filterHandler = FILTERS.get(entry.getKey().toLowerCase(Locale.ROOT));
          if (filterHandler == null) {
            log.warn("Filter '{}' isn't currently supported. Ignoring.", entry.getKey());
          } else if (entry.getValue() != null) {
            var condition = filterHandler.apply(entry.getValue().toString());
            if (condition != null) {
              criteria = criteria.and(condition);
            }
          }
        }

      } catch (IllegalArgumentException ex) {
        throw new ExportServiceException(
            Response.Status.BAD_REQUEST.getStatusCode(),
            "Wrong filter in export request: " + ex.getMessage());
      }
    }

    return criteria;
  }

  private static Specification<SubscriptionCapacityView> handleProductIdFilter(String value) {
    var productId = ProductId.fromString(value).getValue();
    return (root, query, builder) ->
        builder.equal(root.get(SubscriptionCapacityView_.productTag), productId);
  }

  private static Specification<SubscriptionCapacityView> handleUsageFilter(String value) {
    Usage usage = Usage.fromString(value);
    if (value.equalsIgnoreCase(usage.getValue())) {
      if (!Usage._ANY.equals(usage)) {
        return (root, query, builder) ->
            builder.equal(root.get(SubscriptionCapacityView_.usage), usage.getValue());
      }
    } else {
      throw new IllegalArgumentException(String.format("usage: %s not supported", value));
    }

    return null;
  }

  private static Specification<SubscriptionCapacityView> handleCategoryFilter(String value) {
    String measurementType = getMeasurementTypeFromCategory(value);
    if (measurementType != null) {
      return (root, query, builder) ->
          builder.like(
              builder.upper(builder.function("jsonb_pretty", String.class, root.get("metrics"))),
              "%" + measurementType.toUpperCase(Locale.ROOT) + "%");
    }

    return null;
  }

  private static Specification<SubscriptionCapacityView> handleSlaFilter(String value) {
    ServiceLevel serviceLevel = ServiceLevel.fromString(value);
    if (value.equalsIgnoreCase(serviceLevel.getValue())) {
      if (!ServiceLevel._ANY.equals(serviceLevel)) {
        return (root, query, builder) ->
            builder.equal(
                root.get(SubscriptionCapacityView_.serviceLevel), serviceLevel.getValue());
      }
    } else {
      throw new IllegalArgumentException(String.format("sla: %s not supported", value));
    }

    return null;
  }

  private static Specification<SubscriptionCapacityView> handleMetricIdFilter(String value) {
    String metricId = MetricId.fromString(value).toUpperCaseFormatted();
    return (root, query, builder) ->
        builder.like(
            builder.upper(builder.function("jsonb_pretty", String.class, root.get("metrics"))),
            "%" + metricId + "%");
  }

  private static Specification<SubscriptionCapacityView> handleBillingProviderFilter(String value) {
    BillingProvider billingProvider = BillingProvider.fromString(value);
    if (value.equalsIgnoreCase(billingProvider.getValue())) {
      if (!BillingProvider._ANY.equals(billingProvider)) {
        return (root, query, builder) ->
            builder.equal(root.get(SubscriptionCapacityView_.billingProvider), billingProvider);
      }
    } else {
      throw new IllegalArgumentException(
          String.format("billing_provider: %s not supported", value));
    }

    return null;
  }

  private static Specification<SubscriptionCapacityView> handleBillingAccountIdFilter(
      String value) {
    if (!ANY.equalsIgnoreCase(value)) {
      return (root, query, builder) ->
          builder.like(root.get(SubscriptionCapacityView_.billingAccountId), value + "%");
    }

    return null;
  }

  protected static List<SubscriptionsExportJsonMeasurement> groupMetrics(
      SubscriptionCapacityView dataItem, ExportServiceRequest request) {
    List<SubscriptionsExportJsonMeasurement> metrics = new ArrayList<>();

    // metric filters: metric_id and measurement_type
    String filterByMetricId = getMetricIdFilter(request);
    String filterByMeasurementType = getMeasurementTypeFilter(request);

    for (var metric : dataItem.getMetrics()) {
      if (metric.getMetricId() != null
          && isFilterByMetricId(metric, filterByMetricId)
          && isFilterByMeasurementType(metric, filterByMeasurementType)) {
        var measurement = getOrCreateMeasurement(metrics, metric);
        measurement.setCapacity(measurement.getCapacity() + metric.getCapacity());
      }
    }

    return metrics;
  }

  private static boolean isFilterByMetricId(SubscriptionCapacityViewMetric metric, String filter) {
    return filter == null || filter.equalsIgnoreCase(metric.getMetricId());
  }

  private static boolean isFilterByMeasurementType(
      SubscriptionCapacityViewMetric metric, String filter) {
    return filter == null || filter.equalsIgnoreCase(metric.getMeasurementType());
  }

  private static String getMetricIdFilter(ExportServiceRequest request) {
    if (request == null || request.getFilters() == null) {
      return null;
    }

    return Optional.ofNullable(request.getFilters().get(METRIC_ID))
        .map(String.class::cast)
        .orElse(null);
  }

  private static String getMeasurementTypeFilter(ExportServiceRequest request) {
    if (request != null
        && request.getFilters() != null
        && request.getFilters().get(CATEGORY) instanceof String value) {
      return getMeasurementTypeFromCategory(value);
    }

    return null;
  }

  private static String getMeasurementTypeFromCategory(String value) {
    var category = HypervisorReportCategory.mapCategory(ReportCategory.fromString(value));
    if (category != null) {
      return switch (category) {
        case NON_HYPERVISOR -> PHYSICAL;
        case HYPERVISOR -> HYPERVISOR;
      };
    }
    return null;
  }

  private static SubscriptionsExportJsonMeasurement getOrCreateMeasurement(
      List<SubscriptionsExportJsonMeasurement> metrics, SubscriptionCapacityViewMetric metric) {
    return metrics.stream()
        .filter(
            m ->
                Objects.equals(m.getMetricId(), metric.getMetricId())
                    && Objects.equals(m.getMeasurementType(), metric.getMeasurementType()))
        .findFirst()
        .orElseGet(
            () -> {
              var m = new SubscriptionsExportJsonMeasurement();
              m.setMeasurementType(metric.getMeasurementType());
              m.setCapacity(0.0);
              m.setMetricId(metric.getMetricId());
              metrics.add(m);
              return m;
            });
  }
}
